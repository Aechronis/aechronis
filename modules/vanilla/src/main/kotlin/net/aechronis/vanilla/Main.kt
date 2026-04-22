package net.aechronis.vanilla

import net.aechronis.vanilla.commands.Commands
import net.aechronis.vanilla.nbt.NBT
import net.aechronis.vanilla.recpies.Recpies
import net.minestom.server.MinecraftServer

object Main {
    fun init(config: Config = Config()) {
        // measure load time
        val timeStart = System.currentTimeMillis()
        MinecraftServer
            .getInstanceManager()
            .instances
            .firstOrNull()
            ?.timeSynchronizationTicks = 0

        // init
        Commands.init()
        NBT.init()
        Recpies.init()

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Enabled in ${timeLoad}ms")
    }
}
