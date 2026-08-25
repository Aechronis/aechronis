package net.aechronis.guard.listeners

import net.aechronis.guard.Guard
import net.aechronis.guard.flags.FlagName
import net.minestom.server.event.player.PlayerBlockPlaceEvent

object BlockPlaceListener {
    fun handle(event: PlayerBlockPlaceEvent) {
        if (event.isCancelled) return
        Guard.check(
            event.player,
            event.blockPosition.blockX(),
            event.blockPosition.blockY(),
            event.blockPosition.blockZ(),
            FlagName.BLOCK_PLACE,
        ) {
            event.isCancelled = true
        }
    }
}
