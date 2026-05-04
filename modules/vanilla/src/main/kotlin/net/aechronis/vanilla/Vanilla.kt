package net.aechronis.vanilla

import net.aechronis.vanilla.commands.FlyCommand
import net.aechronis.vanilla.commands.GameModeCommand
import net.aechronis.vanilla.commands.MessageCommand
import net.aechronis.vanilla.commands.ReplyCommand
import net.aechronis.vanilla.commands.TeleportCommand
import net.aechronis.vanilla.playerdata.PlayerData
import net.aechronis.vanilla.recpies.Recpies
import net.minestom.server.MinecraftServer
import java.nio.file.Path

object Vanilla {
    fun init(config: VanillaConfig = VanillaConfig()) {
        // measure load time
        val timeStart = System.currentTimeMillis()

        // init commands
        MinecraftServer.getCommandManager().register(
            MessageCommand(),
            ReplyCommand(),
            GameModeCommand(),
            TeleportCommand(),
            FlyCommand(),
        )

        PlayerData.init(Path.of(config.playerDataPath))
        Recpies.init()
        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Enabled in ${timeLoad}ms")
    }
}
