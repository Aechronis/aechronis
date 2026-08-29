package net.aechronis.watchdog.objects

import net.minestom.server.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object PlayerStateReg {
    private val states = ConcurrentHashMap<UUID, PlayerState>()
    var currentTick = 0L

    fun getOrCreate(player: Player): PlayerState = states.computeIfAbsent(player.uuid, ::PlayerState)

    fun remove(player: Player) {
        states.remove(player.uuid)
    }

    fun clear() {
        states.clear()
        currentTick = 0L
    }
}
