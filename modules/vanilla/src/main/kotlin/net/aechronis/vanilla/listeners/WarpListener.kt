package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Warps
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.event.player.PlayerMoveEvent

object WarpListener {
    fun onMove(event: PlayerMoveEvent) {
        val current = event.player.position
        val destination = event.newPosition
        if (current.x() == destination.x() && current.y() == destination.y() && current.z() == destination.z()) return
        if (Warps.cancelPending(event.player)) {
            event.player.sendMessage(Component.text("Warp cancelled because you moved.", NamedTextColor.RED))
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerMoveEvent::class.java, ::onMove)
    }
}
