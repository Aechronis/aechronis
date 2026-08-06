package net.aechronis.guard.listeners

import net.aechronis.guard.Guard
import net.aechronis.guard.flags.FlagName
import net.minestom.server.event.player.PlayerBlockInteractEvent

object BlockInteractListener {
    fun handle(event: PlayerBlockInteractEvent) {
        if (event.isCancelled) return
        Guard.check(
            event.player,
            event.instance,
            event.blockPosition.blockX(),
            event.blockPosition.blockY(),
            event.blockPosition.blockZ(),
            FlagName.BLOCK_INTERACT,
        ) {
            event.isCancelled = true
        }
    }
}
