package net.aechronis.vanilla.managers

import net.aechronis.vanilla.listeners.BoatListener

object Boats {
    fun init() {
        val timeStart = System.currentTimeMillis()
        BoatListener.init()
        println("├─ Boats enabled in ${System.currentTimeMillis() - timeStart}ms")
    }
}
