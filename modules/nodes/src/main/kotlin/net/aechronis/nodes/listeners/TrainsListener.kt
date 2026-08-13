package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Trains
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.block.Block

object TrainsListener {
    private fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.isCancelled || event.hand != PlayerHand.MAIN || event.block != Block.RAIL) return
        Trains.startJourney(event.player, event.instance, event.blockPosition).onSuccess {
            event.isBlockingItemUse = true
        }.onFailure { error ->
            if (error.message != "No train leaves from this rail") {
                Message.error(event.player, error.message ?: "Unable to start train journey")
            }
        }
    }

    private fun onMove(event: PlayerMoveEvent) {
        Trains.cancelIfMoved(event.player, event.newPosition.asBlockVec())
    }

    private fun onTeleport(event: EntityTeleportEvent) {
        val player = event.entity as? net.minestom.server.entity.Player ?: return
        Trains.cancel(player, "Train cancelled due to teleportation")
    }

    private fun onDisconnect(event: PlayerDisconnectEvent) {
        Trains.cancel(event.player)
    }

    private fun onBlockBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled || event.block != Block.GOLD_BLOCK) return
        Trains.removeAt(event.blockPosition)?.let { station ->
            Message.print(event.player, "Station ${station.id} removed")
        }
    }

    fun init() {
        Nodes.lowPriorityEventNode.addListener(PlayerBlockInteractEvent::class.java, this::onInteract)
        Nodes.eventNode.addListener(PlayerMoveEvent::class.java, this::onMove)
        Nodes.eventNode.addListener(EntityTeleportEvent::class.java, this::onTeleport)
        Nodes.eventNode.addListener(PlayerDisconnectEvent::class.java, this::onDisconnect)
        Nodes.lowPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreak)
    }
}
