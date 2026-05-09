package net.aechronis.library

import net.minestom.server.MinecraftServer

object Main {
    fun init() {
        // measure load time
        val timeStart = System.currentTimeMillis()

        MinecraftServer
            .getInstanceManager()
            .instances
            .firstOrNull()
            ?.timeSynchronizationTicks = 0

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("example lib Enabled in ${timeLoad}ms")
    }
}
