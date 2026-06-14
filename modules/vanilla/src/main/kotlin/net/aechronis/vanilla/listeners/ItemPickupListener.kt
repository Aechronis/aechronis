package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.item.PickupItemEvent

object ItemPickupListener {
    fun onPickup(event: PickupItemEvent) {
        val player = event.livingEntity as? Player
        // Only players collect items, and spectators should pass through them. Cancelling keeps the
        // item entity on the ground instead of letting Minestom silently consume it.
        if (player == null || player.gameMode == GameMode.SPECTATOR) {
            event.isCancelled = true
            return
        }

        // addItemStack is all-or-nothing, so a full inventory leaves the item on the ground.
        event.isCancelled = !player.inventory.addItemStack(event.itemStack)
    }

    fun init() {
        Vanilla.eventNode.addListener(PickupItemEvent::class.java, ItemPickupListener::onPickup)
    }
}
