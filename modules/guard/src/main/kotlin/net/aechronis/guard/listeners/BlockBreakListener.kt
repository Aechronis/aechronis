package net.aechronis.guard.listeners

import net.aechronis.guard.Guard
import net.aechronis.guard.flags.FlagName
import net.minestom.server.event.player.PlayerBlockBreakEvent

object BlockBreakListener {
    fun handle(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return
        Guard.check(
            event.player,
            event.instance,
            event.blockPosition.blockX(),
            event.blockPosition.blockY(),
            event.blockPosition.blockZ(),
            FlagName.BLOCK_BREAK,
        ) {
            event.isCancelled = true
        }
    }
}
