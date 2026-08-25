package net.aechronis.guard.listeners

import net.aechronis.guard.Guard
import net.aechronis.guard.flags.FlagName
import net.minestom.server.event.player.PlayerMoveEvent

object MoveListener {
    fun handle(event: PlayerMoveEvent) {
        if (event.isCancelled) return
        val position = event.newPosition
        Guard.check(event.player, position.blockX(), position.blockY(), position.blockZ(), FlagName.TELEPORT) {
            event.isCancelled = true
        }
    }
}
