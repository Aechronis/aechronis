package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Items
import net.minestom.server.event.item.ItemDropEvent

object ItemDropListener {
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

    fun init() {
        Vanilla.eventNode.addListener(ItemDropEvent::class.java, ItemDropListener::onDrop)
    }
}
