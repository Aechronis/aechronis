package net.aechronis.vanilla

import net.aechronis.vanilla.commands.Commands
import net.aechronis.vanilla.nbt.NBT
import net.aechronis.vanilla.recpies.Recpies

object Vanilla {
    fun init(config: VanillaConfig = VanillaConfig()) {
        // measure load time
        val timeStart = System.currentTimeMillis()

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
