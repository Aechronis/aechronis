package net.aechronis.vanilla

import net.aechronis.vanilla.blocks.Blocks
import net.aechronis.vanilla.commands.Commands
import net.aechronis.vanilla.playerdata.PlayerData
import net.aechronis.vanilla.recpies.Recpies
import net.minestom.server.MinecraftServer
import net.minestom.server.ServerProcess
import java.nio.file.Path

object Vanilla {
    fun init(config: VanillaConfig = VanillaConfig()) {
        // measure load time
        val timeStart = System.currentTimeMillis()
        // init
        Commands.init()
        PlayerData.init(Path.of(config.playerDataPath))
        Recpies.init()
        // Register blocks before init.
        Blocks.init()
        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Enabled in ${timeLoad}ms")
    }
}
