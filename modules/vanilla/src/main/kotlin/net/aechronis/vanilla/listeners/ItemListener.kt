package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Items
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.item.PickupItemEvent

object ItemListener {
    fun onDrop(event: ItemDropEvent) {
        val player = event.player
        val instance =
            player.instance ?: run {
                event.isCancelled = true
                return
            }

        val config = Vanilla.config!!
        val direction = player.position.direction()
        val velocity = direction.mul(config.dropThrowVelocity).add(0.0, config.dropThrowUpwardVelocity, 0.0)
        val position = player.position.add(0.0, config.dropSpawnHeight, 0.0)

        Items.spawn(instance, position, event.itemStack, velocity)
    }

    fun onPickup(event: PickupItemEvent) {
        val player = event.livingEntity as? Player
        // Only players collect items, and spectators should pass through them. Cancelling keeps the
        // item entity on the ground instead of letting Minestom silently consume it.
        if (player == null || player.gameMode == GameMode.SPECTATOR) {
            event.isCancelled = true
            return
        }

        val stack = event.itemStack
        val distributable = stack.withMaxStackSize(stack.material().maxStackSize())
        event.isCancelled = !player.inventory.addItemStack(distributable)
    }

    fun init() {
        Vanilla.eventNode.addListener(PickupItemEvent::class.java, ItemListener::onPickup)
        Vanilla.eventNode.addListener(ItemDropEvent::class.java, ItemListener::onDrop)
    }
}
