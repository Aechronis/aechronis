package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Storage
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.instance.block.Block

object StorageOpenListener {
    fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        if (!event.block.compare(Block.BARREL)) return

        val player = event.player
        if (player.isSneaking && !player.itemInMainHand.isAir) return

        event.isCancelled = true
        val instance = player.instance ?: return
        val key = Storage.keyFor(instance, event.blockPosition.asVec())
        val contents = Storage.loadOrCreate(key)
        player.openInventory(contents.inventory)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, StorageOpenListener::onInteract)
    }
}
