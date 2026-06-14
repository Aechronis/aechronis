package net.aechronis.vanilla

import net.aechronis.vanilla.commands.ConvertCommand
import net.aechronis.vanilla.commands.FlyCommand
import net.aechronis.vanilla.commands.GameModeCommand
import net.aechronis.vanilla.commands.GiveCommand
import net.aechronis.vanilla.commands.KillCommand
import net.aechronis.vanilla.commands.MessageCommand
import net.aechronis.vanilla.commands.ReplyCommand
import net.aechronis.vanilla.commands.ShopCommand
import net.aechronis.vanilla.commands.TeleportCommand
import net.aechronis.vanilla.listeners.FallDamageListener
import net.aechronis.vanilla.listeners.PlayerBreakListener
import net.aechronis.vanilla.managers.Blocks
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.managers.Elevator
import net.aechronis.vanilla.managers.Food
import net.aechronis.vanilla.managers.Items
import net.aechronis.vanilla.managers.Mannequin
import net.aechronis.vanilla.managers.PlayerData
import net.aechronis.vanilla.managers.Recipes
import net.aechronis.vanilla.managers.Shop
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.managers.TreeFeller
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
            MessageCommand(),
            ReplyCommand(),
            GameModeCommand(),
            GiveCommand(),
            TeleportCommand(),
            FlyCommand(),
            KillCommand(),
            ConvertCommand(),
            ShopCommand(),
        )

        PlayerData.init(Path.of(config!!.path, config!!.playerDataPath))
        Storage.init(Path.of(config!!.path, config!!.storagePath))
        Recipes.init()
        Crops.init()
        Elevator.init()
        Mannequin.init()
        Blocks.init()
        TreeFeller.init()
        Food.init()
        Shop.init()
        Items.init()
        PlayerBreakListener.init()
        FallDamageListener.init()
        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println(
            "Vanilla " +
                "Enabled in ${timeLoad}ms",
        )
    }
}
