package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.utils.Notifications
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.item.ItemStack

object PlayerBreakListener {
    fun onBlockBreak(event: PlayerBlockBreakEvent) {
        val player = event.player
        val block = event.block
        if (player.gameMode == GameMode.CREATIVE) return
        val material = block.registry()?.material() ?: return
        if (!player.inventory.addItemStack(ItemStack.of(material))) {
            event.isCancelled = true
            player.sendNotification(Notifications.fullInv)
            return
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, PlayerBreakListener::onBlockBreak)
    }
}
