package net.aechronis.vanilla

import net.aechronis.vanilla.commands.FlyCommand
import net.aechronis.vanilla.commands.GameModeCommand
import net.aechronis.vanilla.commands.GiveCommand
import net.aechronis.vanilla.commands.MessageCommand
import net.aechronis.vanilla.commands.ReplyCommand
import net.aechronis.vanilla.commands.TeleportCommand
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.managers.Elevator
import net.aechronis.vanilla.managers.PlayerData
import net.aechronis.vanilla.managers.Recipes
import net.aechronis.vanilla.managers.Storage
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import java.nio.file.Path

object Vanilla {
    val eventNode = EventNode.all("vanilla")

    fun init(config: VanillaConfig = VanillaConfig()) {
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
        )

        PlayerData.init(Path.of(config.playerDataPath))
        Recipes.init()
        Crops.init(config)
        Elevator.init()
        Storage.init(Path.of(config.storagePath))
        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println(
            "Vanilla " +
                "Enabled in ${timeLoad}ms",
        )
    }
}
