package net.aechronis.nodes

import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.objects.NationFlagPack
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.FlagWar
import net.aechronis.server.events.SpawnPointChangedEvent
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModulePermissions
import net.aechronis.stats.Stats
import java.util.concurrent.CompletableFuture

class NodesModule : AechronisModule {
    override val id = "nodes"
    override val dependencies = setOf("utils", "combat", "economy", "vanilla", "regenerating-ores", "worldedit", "stats")

    override fun initialize(context: ModuleContext) {
        ModulePermissions.register("nodes.bypass", "*")
        Nodes.initialize(takeConfiguration(context))
        Stats.nationOf = { Resident.fromUuid(it)?.town?.nation?.name }
        Stats.warActive = { FlagWar.enabled }
        Stats.areFriendly = { a, b ->
            Town.relationshipOfPlayerToPlayer(a, b) in setOf(DiplomaticRelationship.TOWN, DiplomaticRelationship.NATION, DiplomaticRelationship.ALLY)
        }
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
