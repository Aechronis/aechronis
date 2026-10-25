package net.aechronis.warps.listeners

import net.aechronis.server.modules.ModuleEvents
import net.aechronis.warps.Warps
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent

object WarpListener {
    private val eventNode = EventNode.all("warps")

    fun onTeleport(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return
        Warps.saveLastLocation(player)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        Warps.disconnect(event.player)
    }

    fun onMove(event: PlayerMoveEvent) {
        val current = event.player.position
        val destination = event.newPosition
        if (current.x() == destination.x() && current.y() == destination.y() && current.z() == destination.z()) return
        if (Warps.cancelPending(event.player)) {
            event.player.sendMessage(Component.text("Warp cancelled because you moved.", NamedTextColor.RED))
        }
    }

    fun init() {
        eventNode.addListener(PlayerMoveEvent::class.java, ::onMove)
        eventNode.addListener(EntityTeleportEvent::class.java, ::onTeleport)
        eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
    }
}
