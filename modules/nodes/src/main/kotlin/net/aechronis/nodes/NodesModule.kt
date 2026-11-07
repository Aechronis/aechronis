package net.aechronis.nodes

import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.objects.MinimapMode
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.NationFlagPack
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.FlagWar
import net.aechronis.server.events.SpawnPointChangedEvent
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModulePermissions
import net.aechronis.settings.PlayerSetting
import net.aechronis.settings.PlayerSettings
import net.aechronis.stats.Stats
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.item.Material
import java.util.concurrent.CompletableFuture

class NodesModule : AechronisModule {
    override val id = "nodes"
    override val dependencies = setOf("utils", "combat", "economy", "vanilla", "regenerating-ores", "worldedit", "stats", "settings")

    /** Position, shift and north lock only affect the modern HUD map; say so instead of silently doing nothing. */
    private fun rejectLegacy(
        player: Player,
        resident: Resident,
    ): Boolean {
        if (resident.minimapMode != MinimapMode.LEGACY) return false
        player.sendMessage(Component.text("That only applies to the modern map; set Minimap mode to modern first.", NamedTextColor.RED))
        return true
    }

    private val minimapSettings =
        arrayOf(
            PlayerSetting(
                "Minimap mode",
                Material.FILLED_MAP,
                MinimapMode.entries.map { it.id },
                { Resident.fromPlayer(it)?.minimapMode?.id ?: MinimapMode.OFF.id },
                { player, value ->
                    val resident = Resident.fromPlayer(player) ?: return@PlayerSetting false
                    resident.setMinimapMode(MinimapMode.entries.first { it.id == value })
                    true
                },
            ),
            PlayerSetting(
                "Minimap position (modern map)",
                Material.COMPASS,
                MinimapPosition.entries.map { it.id },
                { (Resident.fromPlayer(it)?.minimapPosition ?: MinimapPosition.DEFAULT).id },
                { player, value ->
                    val resident = Resident.fromPlayer(player) ?: return@PlayerSetting false
                    if (rejectLegacy(player, resident)) return@PlayerSetting true
                    resident.setMinimapPosition(MinimapPosition.fromId(value) ?: return@PlayerSetting false)
                    true
                },
            ),
            PlayerSetting.toggle(
                "Enlarge minimap while sneaking (modern map)",
                Material.SPYGLASS,
                { Resident.fromPlayer(it)?.minimapShiftEnabled ?: true },
                { player, on ->
                    val resident = Resident.fromPlayer(player) ?: return@toggle false
                    if (rejectLegacy(player, resident)) return@toggle true
                    if (resident.minimapShiftEnabled != on) resident.toggleMinimapShift()
                    true
                },
            ),
            PlayerSetting.toggle(
                "Lock minimap to north (modern map)",
                Material.RECOVERY_COMPASS,
                { Resident.fromPlayer(it)?.minimapNorthLocked ?: true },
                { player, on ->
                    val resident = Resident.fromPlayer(player) ?: return@toggle false
                    if (rejectLegacy(player, resident)) return@toggle true
                    if (resident.minimapNorthLocked != on) resident.toggleMinimapNorthLock()
                    true
                },
            ),
            PlayerSetting.toggle(
                "Death waypoints",
                Material.SKELETON_SKULL,
                { Resident.fromPlayer(it)?.deathWaypointsEnabled ?: true },
                { player, on ->
                    val resident = Resident.fromPlayer(player) ?: return@toggle false
                    resident.setDeathWaypointsEnabled(on)
                    true
                },
            ),
        )

    override fun initialize(context: ModuleContext) {
        ModulePermissions.register("nodes.bypass", "*")
        Nodes.initialize(takeConfiguration(context))
        MapPositionApi.start(Nodes.config.mapApiPort)
        Stats.nationOf = { Resident.fromUuid(it)?.town?.nation?.name }
        Stats.warActive = { FlagWar.enabled }
        Stats.areFriendly = { a, b ->
            Town.relationshipOfPlayerToPlayer(a, b) in setOf(DiplomaticRelationship.TOWN, DiplomaticRelationship.NATION, DiplomaticRelationship.ALLY)
        }
        PlayerSettings.register(*minimapSettings)
        NationFlagPack.initialize(context)
        context.addListener(SpawnPointChangedEvent::class.java) { event ->
            Nodes.config.defaultRespawnPoint = event.spawnPoint
        }
    }

    override fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> = context.captureLive { Nodes.saveWorld(checkIfNeedsSave = true, async = true) }

    override fun prepareForShutdown(context: ModuleContext) {
        // Combat is a dependency and normally prepares after Nodes. Restore its temporary block
        // replacements first so clearing a war flag remains authoritative in the core checkpoint.
        BlockRestoreManager.flush()
        Nodes.prepareForShutdown()
        // Nodes teardown removes temporary war structures and performs its final durable save.
        // Run it before the core world checkpoint so shutdown/reload cannot persist ghost flags.
        Nodes.cleanup { action -> context.captureLive(action) }
    }

    override fun shutdown(context: ModuleContext) {
        PlayerSettings.unregister(*minimapSettings)
        MapPositionApi.stop()
        Stats.nationOf = { null }
        Stats.warActive = { false }
        Stats.areFriendly = { _, _ -> false }
        NationFlagPack.shutdown()
        Nodes.cleanup()
    }

    companion object {
        private var configured: NodesConfig? = null

        fun configure(config: NodesConfig) {
            check(configured == null) { "Nodes was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(context: ModuleContext): NodesConfig = configured.also { configured = null } ?: NodesConfig(defaultRespawnPoint = context.spawnPoint)
    }
}
