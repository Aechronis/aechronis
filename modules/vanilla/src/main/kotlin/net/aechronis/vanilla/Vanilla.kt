package net.aechronis.vanilla

import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.aechronis.vanilla.commands.Back
import net.aechronis.vanilla.commands.Broadcast
import net.aechronis.vanilla.commands.Clear
import net.aechronis.vanilla.commands.Convert
import net.aechronis.vanilla.commands.Craft
import net.aechronis.vanilla.commands.EnderChest
import net.aechronis.vanilla.commands.Fly
import net.aechronis.vanilla.commands.GameMode
import net.aechronis.vanilla.commands.Give
import net.aechronis.vanilla.commands.Gm
import net.aechronis.vanilla.commands.Ignore
import net.aechronis.vanilla.commands.InventorySee
import net.aechronis.vanilla.commands.Kill
import net.aechronis.vanilla.commands.KothCommand
import net.aechronis.vanilla.commands.List
import net.aechronis.vanilla.commands.Message
import net.aechronis.vanilla.commands.Music
import net.aechronis.vanilla.commands.Ore
import net.aechronis.vanilla.commands.Recpies
import net.aechronis.vanilla.commands.Reply
import net.aechronis.vanilla.commands.Shop
import net.aechronis.vanilla.commands.Shutdown
import net.aechronis.vanilla.commands.Teleport
import net.aechronis.vanilla.commands.TpsBar
import net.aechronis.vanilla.commands.Vanish
import net.aechronis.vanilla.commands.Vote
import net.aechronis.vanilla.commands.Warp
import net.aechronis.vanilla.commands.Whitelist
import net.aechronis.vanilla.listeners.BlockPlacementCooldownListener
import net.aechronis.vanilla.listeners.CombatInventoryListener
import net.aechronis.vanilla.listeners.CommandsListener
import net.aechronis.vanilla.listeners.FallDamageListener
import net.aechronis.vanilla.listeners.PlayerActivityListener
import net.aechronis.vanilla.listeners.PlayerBreakListener
import net.aechronis.vanilla.listeners.ServerLinksListener
import net.aechronis.vanilla.listeners.WarpListener
import net.aechronis.vanilla.managers.Blocks
import net.aechronis.vanilla.managers.Boats
import net.aechronis.vanilla.managers.Bundles
import net.aechronis.vanilla.managers.Combat
import net.aechronis.vanilla.managers.Commands
import net.aechronis.vanilla.managers.Crates
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.managers.Efficiency
import net.aechronis.vanilla.managers.Elevator
import net.aechronis.vanilla.managers.EnvironmentalDamage
import net.aechronis.vanilla.managers.Filter
import net.aechronis.vanilla.managers.Food
import net.aechronis.vanilla.managers.ItemFrames
import net.aechronis.vanilla.managers.Items
import net.aechronis.vanilla.managers.KillShop
import net.aechronis.vanilla.managers.Koth
import net.aechronis.vanilla.managers.Mannequin
import net.aechronis.vanilla.managers.Ores
import net.aechronis.vanilla.managers.PlayerData
import net.aechronis.vanilla.managers.Recipes
import net.aechronis.vanilla.managers.Saplings
import net.aechronis.vanilla.managers.Shelves
import net.aechronis.vanilla.managers.Signs
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.managers.TreeFeller
import net.aechronis.vanilla.managers.VoteLinks
import net.aechronis.vanilla.managers.Warps
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import net.aechronis.vanilla.managers.Music as MusicManager
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
    ) {
        config = c
        ShutdownManager.configure(shutdownAction)

        measure("Player Activity Listener") { PlayerActivityListener.init() }
        measure("Block Placement Cooldown Listener") { BlockPlacementCooldownListener.init() }
        measure("Combat Inventory Listener") { CombatInventoryListener.init() }
        measure("Warp Listener") { WarpListener.init() }
        measure("Filter") { Filter.init() }

        if (config.commandsEnabled) {
            measure("Commands") {
                val commands =
                    mutableListOf(
                        Back(),
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
                        Warp(),
                    )
                if (config.musicEnabled) commands += Music()
                if (config.blocksEnabled) commands += Convert()
                if (config.recipesEnabled) commands += Craft()
                if (config.recipesEnabled || config.blocksEnabled) commands += Recpies()
                if (config.shopEnabled) commands += Shop()
                if (config.whitelistEnabled) commands += Whitelist()
                if (config.kothEnabled) commands += KothCommand()
                if (config.oresEnabled) commands += Ore()
                ModuleCommands
                    .register(*commands.toTypedArray())
            }
        }
        val playerDataEventNode =
            if (config.playerDataEnabled) measure("Player data") { PlayerData.init(Path.of(config.path, config.playerDataPath)) } else null
        if (config.storageEnabled) measure("Storage") { Storage.init(Path.of(config.path, config.storagePath)) }
        if (config.signsEnabled) measure("Signs") { Signs.init() }
        if (config.shelvesEnabled) measure("Shelves") { Shelves.init() }
        if (config.itemFramesEnabled) measure("Item Frames") { ItemFrames.init() }
        if (config.whitelistEnabled) measure("Whitelist") { WhitelistManager.init(Path.of(config.path, config.whitelistPath)) }
        if (config.recipesEnabled) measure("Recipes") { Recipes.init() }
        if (config.cropsEnabled) measure("Crops") { Crops.init() }
        if (config.saplingsEnabled) measure("Saplings") { Saplings.init() }
        if (config.elevatorEnabled) measure("Elevator") { Elevator.init() }
        if (config.mannequinEnabled) measure("Mannequin") { Mannequin.init() }
        if (config.blocksEnabled) measure("Blocks") { Blocks.init() }
        if (config.treeFellerEnabled) measure("Tree Feller") { TreeFeller.init() }
        if (config.foodEnabled) measure("Food") { Food.init() }
        if (config.shopEnabled) measure("Shop") { KillShop.init() }
        if (config.cratesEnabled) measure("Crates") { Crates.init() }
        if (config.itemsEnabled) measure("Items") { Items.init() }
        if (config.bundlesEnabled) measure("Bundles") { Bundles.init() }
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
        if (config.combatEnabled) measure("Combat") { Combat.init() }
        if (config.musicEnabled) measure("Music") { MusicManager.init() }
        if (config.kothEnabled) measure("Koth") { Koth.init(Path.of(config.path, config.kothsPath)) }
        if (config.oresEnabled) measure("Ores") { Ores.init(Path.of(config.path, config.oresPath)) }
        measure("Vote Links") { VoteLinks.init(Path.of(config.path, config.votePath)) }
        measure("Warps") { Warps.init(Path.of(config.path, config.warpsPath)) }
        val globalEventHandler = MinecraftServer.getGlobalEventHandler()
        ModuleEvents.addChild(globalEventHandler, eventNode)
        playerDataEventNode?.let { node -> ModuleEvents.addChild(globalEventHandler, node) }
    }

    /** Called by the server's coordinated shutdown hook after vehicles have ejected their riders. */
    fun saveBeforeShutdown(context: ModuleContext) {
        println("Vanilla: saving data before shutdown...")
        runSaveStages(
            "checkpoint" to { context.captureLive(::saveCheckpoint).join() },
            "ores" to { if (config.oresEnabled) Ores.saveAll() },
            "koth" to { if (config.kothEnabled) Koth.saveAll() },
            "warps" to Warps::saveAll,
        )
        println("Vanilla: data saved.")
    }

    fun shutdown() {
        runSaveStages(
            "player data writer" to PlayerData::shutdown,
            "crate rolls" to Crates::shutdown,
            "corpse loot" to Mannequin::shutdown,
            "item frames" to ItemFrames::shutdown,
            "koth state" to Koth::shutdown,
            "combat bars" to Combat::shutdown,
            "tps bars" to TpsBarManager::shutdown,
            "vanish state" to VanishManager::shutdown,
            "efficiency attributes" to Efficiency::shutdown,
            "ore cooldowns" to { if (config.oresEnabled) Ores.shutdown() },
            "crop growth" to { if (config.cropsEnabled) Crops.shutdown() },
            "sapling growth" to { if (config.saplingsEnabled) Saplings.shutdown() },
            "recipes" to Recipes::shutdown,
            "storage" to Storage::shutdown,
            "sign editors" to Signs::shutdown,
            "pending warps" to Warps::shutdown,
            "shop inventories" to KillShop::shutdown,
            "command inventories" to Commands::shutdown,
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
