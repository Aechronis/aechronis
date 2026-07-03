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
import net.aechronis.vanilla.listeners.FallDamageListener
import net.aechronis.vanilla.listeners.PlayerBreakListener
import net.aechronis.vanilla.listeners.ServerLinksListener
import net.aechronis.vanilla.managers.Blocks
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
import net.aechronis.vanilla.managers.Whitelist as WhitelistManager
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import java.nio.file.Path

object Vanilla {
    val eventNode = EventNode.all("vanilla")
    var config: VanillaConfig? = null

    fun init(c: VanillaConfig = VanillaConfig()) {
        config = c
        // measure load time
        val timeStart = System.currentTimeMillis()

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        // init commands
        MinecraftServer.getCommandManager().register(
            Back(),
            Message(),
            Reply(),
            GameMode(),
            Give(),
            Teleport(),
            Fly(),
            Kill(),
            Convert(),
            Craft(),
            Broadcast(),
            Clear(),
            InventorySee(),
            Ignore(),
            Shop(),
            Gm(),
            List(),
            Whitelist(),
        )

        PlayerData.init(Path.of(config!!.path, config!!.playerDataPath))
        Storage.init(Path.of(config!!.path, config!!.storagePath))
        WhitelistManager.init(Path.of(config!!.path, config!!.whitelistPath))
        Recipes.init()
        Crops.init()
        Saplings.init()
        Elevator.init()
        Mannequin.init()
        Blocks.init()
        TreeFeller.init()
        Food.init()
        KillShop.init()
        Items.init()
        PlayerBreakListener.init()
        FallDamageListener.init()
        ServerLinksListener.init()

        Runtime.getRuntime().addShutdownHook(
            Thread({
                println("Vanilla: saving data before shutdown...")
                PlayerData.saveAll()
                Storage.saveAll()
                println("Vanilla: data saved.")
            }, "vanilla-shutdown-save"),
        )

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println(
            "Vanilla " +
                "Enabled in ${timeLoad}ms",
        )
    }
}
