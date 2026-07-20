package net.aechronis.vanilla

import net.aechronis.vanilla.commands.Back
import net.aechronis.vanilla.commands.Broadcast
import net.aechronis.vanilla.commands.Clear
import net.aechronis.vanilla.commands.Convert
import net.aechronis.vanilla.commands.Craft
import net.aechronis.vanilla.commands.Fly
import net.aechronis.vanilla.commands.GameMode
import net.aechronis.vanilla.commands.Give
import net.aechronis.vanilla.commands.Gm
import net.aechronis.vanilla.commands.Ignore
import net.aechronis.vanilla.commands.InventorySee
import net.aechronis.vanilla.commands.Kill
import net.aechronis.vanilla.commands.List
import net.aechronis.vanilla.commands.Message
import net.aechronis.vanilla.commands.Reply
import net.aechronis.vanilla.commands.Shop
import net.aechronis.vanilla.commands.Teleport
import net.aechronis.vanilla.commands.Whitelist
import net.aechronis.vanilla.listeners.CommandsListener
import net.aechronis.vanilla.listeners.FallDamageListener
import net.aechronis.vanilla.listeners.PlayerBreakListener
import net.aechronis.vanilla.listeners.ServerLinksListener
import net.aechronis.vanilla.managers.Blocks
import net.aechronis.vanilla.managers.Combat
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.managers.Elevator
import net.aechronis.vanilla.managers.Food
import net.aechronis.vanilla.managers.Items
import net.aechronis.vanilla.managers.KillShop
import net.aechronis.vanilla.managers.Mannequin
import net.aechronis.vanilla.managers.PlayerData
import net.aechronis.vanilla.managers.Recipes
import net.aechronis.vanilla.managers.Saplings
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.managers.TreeFeller
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import java.nio.file.Path
import net.aechronis.vanilla.managers.Whitelist as WhitelistManager

object Vanilla {
    val eventNode = EventNode.all("vanilla")
    lateinit var config: VanillaConfig
        private set

    fun init(c: VanillaConfig = VanillaConfig()) {
        config = c
        // measure load time
        val timeStart = System.currentTimeMillis()

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

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
                    InventorySee(),
                    Ignore(),
                    Gm(),
                    List(),
                )
            if (config.blocksEnabled) commands += Convert()
            if (config.recipesEnabled) commands += Craft()
            if (config.shopEnabled) commands += Shop()
            if (config.whitelistEnabled) commands += Whitelist()
            MinecraftServer.getCommandManager().register(*commands.toTypedArray())
        }
        println("Loading Vanilla")
        if (config.playerDataEnabled) PlayerData.init(Path.of(config.path, config.playerDataPath))
        if (config.storageEnabled) Storage.init(Path.of(config.path, config.storagePath))
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
        if (config.itemsEnabled) Items.init()
        if (config.commandsEnabled) CommandsListener.init()
        if (config.blockDropsEnabled) PlayerBreakListener.init()
        if (config.fallDamageEnabled) FallDamageListener.init()
        if (config.serverLinksEnabled) ServerLinksListener.init()
        if (config.combatEnabled) Combat.init()

        Runtime.getRuntime().addShutdownHook(
            Thread({
                println("Vanilla: saving data before shutdown...")
                if (config.playerDataEnabled) PlayerData.saveAll()
                if (config.storageEnabled) Storage.saveAll()
                println("Vanilla: data saved.")
            }, "vanilla-shutdown-save"),
        )

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("└─ Vanilla Loaded in ${timeLoad}ms")
    }
}
