package net.aechronis.vanilla

import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.aechronis.vanilla.commands.Broadcast
import net.aechronis.vanilla.commands.Clear
import net.aechronis.vanilla.commands.Craft
import net.aechronis.vanilla.commands.EnderChest
import net.aechronis.vanilla.commands.Fly
import net.aechronis.vanilla.commands.GameMode
import net.aechronis.vanilla.commands.Give
import net.aechronis.vanilla.commands.Gm
import net.aechronis.vanilla.commands.Ignore
import net.aechronis.vanilla.commands.InventorySee
import net.aechronis.vanilla.commands.Kill
import net.aechronis.vanilla.commands.List
import net.aechronis.vanilla.commands.Message
import net.aechronis.vanilla.commands.Recpies
import net.aechronis.vanilla.commands.Reply
import net.aechronis.vanilla.commands.Shutdown
import net.aechronis.vanilla.commands.Teleport
import net.aechronis.vanilla.commands.TpsBar
import net.aechronis.vanilla.commands.Vanish
import net.aechronis.vanilla.commands.Vote
import net.aechronis.vanilla.commands.Whitelist
import net.aechronis.vanilla.listeners.BlockPlacementCooldownListener
import net.aechronis.vanilla.listeners.CombatInventoryListener
import net.aechronis.vanilla.listeners.CommandsListener
import net.aechronis.vanilla.listeners.FallDamageListener
import net.aechronis.vanilla.listeners.PlayerActivityListener
import net.aechronis.vanilla.listeners.PlayerBreakListener
import net.aechronis.vanilla.listeners.ServerLinksListener
import net.aechronis.vanilla.managers.Boats
import net.aechronis.vanilla.managers.Commands
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.managers.Efficiency
import net.aechronis.vanilla.managers.EnvironmentalDamage
import net.aechronis.vanilla.managers.Filter
import net.aechronis.vanilla.managers.Food
import net.aechronis.vanilla.managers.Horses
import net.aechronis.vanilla.managers.ItemFrames
import net.aechronis.vanilla.managers.Items
import net.aechronis.vanilla.managers.Jukeboxes
import net.aechronis.vanilla.managers.PlayerData
import net.aechronis.vanilla.managers.Recipes
import net.aechronis.vanilla.managers.Saplings
import net.aechronis.vanilla.managers.Shelves
import net.aechronis.vanilla.managers.Signs
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.managers.VoteLinks
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import net.minestom.server.instance.Instance
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import net.aechronis.vanilla.managers.Shutdown as ShutdownManager
import net.aechronis.vanilla.managers.TpsBar as TpsBarManager
import net.aechronis.vanilla.managers.Vanish as VanishManager
import net.aechronis.vanilla.managers.Whitelist as WhitelistManager

object Vanilla {
    val eventNode = EventNode.all("vanilla")
    lateinit var config: VanillaConfig
        private set

    fun init(
        c: VanillaConfig = VanillaConfig(),
        shutdownAction: () -> Unit = { MinecraftServer.stopCleanly() },
        instance: Instance? = null,
    ) {
        config = c
        ShutdownManager.configure(shutdownAction)

        measure("Player Activity Listener") { PlayerActivityListener.init() }
        measure("Block Placement Cooldown Listener") { BlockPlacementCooldownListener.init() }
        measure("Combat Inventory Listener") { CombatInventoryListener.init() }
        measure("Filter") { Filter.init() }

        if (config.commandsEnabled) {
            measure("Commands") {
                val commands =
                    mutableListOf(
                        Message(),
                        Reply(),
                        GameMode(),
                        Give(),
                        Teleport(),
                        Fly(),
                        Kill(),
                        Broadcast(),
                        Clear(),
                        EnderChest(),
                        InventorySee(),
                        Ignore(),
                        Gm(),
                        List(),
                        TpsBar(),
                        Shutdown(),
                        Vanish(),
                        Vote(),
                    )
                if (config.recipesEnabled) commands += Craft()
                if (config.recipesEnabled || config.blocksEnabled) commands += Recpies()
                if (config.whitelistEnabled) commands += Whitelist()
                ModuleCommands
                    .register(*commands.toTypedArray())
            }
        }
        val playerDataEventNode =
            if (config.playerDataEnabled) measure("Player data") { PlayerData.init(Path.of(config.path, config.playerDataPath)) } else null
        if (config.storageEnabled) measure("Storage") { Storage.init() }
        if (config.signsEnabled) measure("Signs") { Signs.init() }
        if (config.shelvesEnabled) measure("Shelves") { Shelves.init() }
        if (config.itemFramesEnabled) measure("Item Frames") { ItemFrames.init() }
        if (config.whitelistEnabled) measure("Whitelist") { WhitelistManager.init(Path.of(config.path, config.whitelistPath)) }
        if (config.recipesEnabled) measure("Recipes") { Recipes.init() }
        if (config.cropsEnabled) measure("Crops") { Crops.init() }
        if (config.saplingsEnabled) measure("Saplings") { Saplings.init() }
        if (config.foodEnabled) measure("Food") { Food.init() }
        if (config.itemsEnabled) measure("Items") { Items.init() }
        if (config.boatsEnabled) measure("Boats") { Boats.init() }
        if (config.efficiencyEnabled) measure("Efficiency") { Efficiency.init() }
        if (config.commandsEnabled) {
            measure("Command listeners") { CommandsListener.init() }
            measure("Vanish") { VanishManager.init() }
            measure("TPS bar") { TpsBarManager.init() }
        }
        if (config.blockDropsEnabled) measure("Player Break Listener") { PlayerBreakListener.init() }
        if (config.fallDamageEnabled) measure("Fall Damage Listener") { FallDamageListener.init() }
        if (config.fireDamageEnabled ||
            config.drowningEnabled ||
            config.voidDamageEnabled
        ) {
            measure("Environmental Damage") { EnvironmentalDamage.init() }
        }
        if (config.serverLinksEnabled) measure("Server Links Listener") { ServerLinksListener.init() }
        measure("Vote Links") { VoteLinks.init(Path.of(config.path, config.votePath)) }
        if (config.horsesEnabled) {
            val world = requireNotNull(instance) { "Horses need the world instance" }
            measure("Horses") { Horses.init(Path.of(config.path, config.horsesPath), world) }
        }
        val globalEventHandler = MinecraftServer.getGlobalEventHandler()
        ModuleEvents.addChild(globalEventHandler, eventNode)
        playerDataEventNode?.let { node -> ModuleEvents.addChild(globalEventHandler, node) }
    }

    /** Called by the server's coordinated shutdown hook after vehicles have ejected their riders. */
    fun saveBeforeShutdown(context: ModuleContext) {
        println("Vanilla: saving data before shutdown...")
        runSaveStages(
            "checkpoint" to { context.captureLive(::saveCheckpoint).join() },
            "horses" to { if (config.horsesEnabled) Horses.save() },
        )
        println("Vanilla: data saved.")
    }

    fun shutdown() {
        runSaveStages(
            "player data writer" to PlayerData::shutdown,
            "item frames" to ItemFrames::shutdown,
            "tps bars" to TpsBarManager::shutdown,
            "vanish state" to VanishManager::shutdown,
            "efficiency attributes" to Efficiency::shutdown,
            "crop growth" to { if (config.cropsEnabled) Crops.shutdown() },
            "sapling growth" to { if (config.saplingsEnabled) Saplings.shutdown() },
            "recipes" to Recipes::shutdown,
            "storage" to Storage::shutdown,
            "sign editors" to Signs::shutdown,
            "command inventories" to Commands::shutdown,
            "horses" to { if (config.horsesEnabled) Horses.shutdown() },
            "jukebox playback" to Jukeboxes::shutdown,
        )
    }

    // flushes player and container state immediately before the containing world is saved
    fun saveCheckpoint(): CompletableFuture<Void> {
        var playerSave = CompletableFuture.completedFuture<Void>(null)
        val captureFailure =
            runCatching {
                runSaveStages(
                    "player data" to { if (config.playerDataEnabled) playerSave = PlayerData.saveAll() },
                    "storage" to { if (config.storageEnabled) Storage.flushToWorld() },
                    "horses" to { if (config.horsesEnabled) Horses.save() },
                )
            }.exceptionOrNull()
        // Even a failed container capture must not hide a still-running player write from unload.
        return CompletableFuture.allOf(
            playerSave,
            captureFailure?.let { CompletableFuture.failedFuture<Void>(it) }
                ?: CompletableFuture.completedFuture(null),
        )
    }

    private fun runSaveStages(vararg stages: Pair<String, () -> Unit>) {
        var failure: Throwable? = null
        stages.forEach { (name, save) ->
            try {
                save()
            } catch (error: Throwable) {
                System.err.println("Vanilla: failed to save $name: ${error.message}")
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
