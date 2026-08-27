package net.aechronis.vanilla

import net.aechronis.vanilla.commands.Back
import net.aechronis.vanilla.commands.Broadcast
import net.aechronis.vanilla.commands.Clear
import net.aechronis.vanilla.commands.Convert
import net.aechronis.vanilla.commands.Craft
import net.aechronis.vanilla.commands.EnderChest
import net.aechronis.vanilla.commands.FactoryCommand
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
import net.aechronis.vanilla.managers.Crates
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.managers.Efficiency
import net.aechronis.vanilla.managers.Elevator
import net.aechronis.vanilla.managers.EnvironmentalDamage
import net.aechronis.vanilla.managers.Factories
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
        // measure load time
        val timeStart = System.currentTimeMillis()

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        PlayerActivityListener.init()
        BlockPlacementCooldownListener.init()
        CombatInventoryListener.init()
        WarpListener.init()
        Filter.init()

        if (config.commandsEnabled) {
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
            if (config.factoriesEnabled) commands += FactoryCommand()
            MinecraftServer.getCommandManager().register(*commands.toTypedArray())
        }
        println("Loading Vanilla")
        if (config.playerDataEnabled) PlayerData.init(Path.of(config.path, config.playerDataPath))
        if (config.storageEnabled) Storage.init(Path.of(config.path, config.storagePath))
        if (config.signsEnabled) Signs.init()
        if (config.shelvesEnabled) Shelves.init()
        if (config.itemFramesEnabled) ItemFrames.init()
        if (config.whitelistEnabled) WhitelistManager.init(Path.of(config.path, config.whitelistPath))
        if (config.recipesEnabled) Recipes.init()
        if (config.cropsEnabled) Crops.init()
        if (config.saplingsEnabled) Saplings.init()
        if (config.elevatorEnabled) Elevator.init()
        if (config.mannequinEnabled) Mannequin.init()
        if (config.blocksEnabled) Blocks.init()
        if (config.treeFellerEnabled) TreeFeller.init()
        if (config.foodEnabled) Food.init()
        if (config.shopEnabled) KillShop.init()
        if (config.cratesEnabled) Crates.init()
        if (config.itemsEnabled) Items.init()
        if (config.bundlesEnabled) Bundles.init()
        if (config.boatsEnabled) Boats.init()
        if (config.efficiencyEnabled) Efficiency.init()
        if (config.commandsEnabled) {
            CommandsListener.init()
            VanishManager.init()
            TpsBarManager.init()
        }
        if (config.blockDropsEnabled) PlayerBreakListener.init()
        if (config.fallDamageEnabled) FallDamageListener.init()
        if (config.fireDamageEnabled || config.drowningEnabled || config.voidDamageEnabled) EnvironmentalDamage.init()
        if (config.serverLinksEnabled) ServerLinksListener.init()
        if (config.combatEnabled) Combat.init()
        if (config.musicEnabled) MusicManager.init()
        if (config.kothEnabled) Koth.init(Path.of(config.path, config.kothsPath))
        if (config.oresEnabled) Ores.init(Path.of(config.path, config.oresPath))
        if (config.factoriesEnabled) Factories.init(Path.of(config.path, config.factoriesPath))
        VoteLinks.init(Path.of(config.path, config.votePath))
        Warps.init(Path.of(config.path, config.warpsPath))

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("└─ Vanilla Loaded in ${timeLoad}ms")
    }

    /** Called by the server's coordinated shutdown hook after vehicles have ejected their riders. */
    fun saveBeforeShutdown() {
        println("Vanilla: saving data before shutdown...")
        runSaveStages(
            "checkpoint" to ::saveCheckpoint,
            "ores" to { if (config.oresEnabled) Ores.saveAll() },
            "factories" to { if (config.factoriesEnabled) Factories.saveAll() },
            "koth" to { if (config.kothEnabled) Koth.saveAll() },
            "warps" to Warps::saveAll,
        )
        println("Vanilla: data saved.")
    }

    // flushes player and container state immediately before the containing world is saved
    fun saveCheckpoint() {
        runSaveStages(
            "player data" to { if (config.playerDataEnabled) PlayerData.saveAll() },
            "storage" to { if (config.storageEnabled) Storage.flushToWorld() },
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
