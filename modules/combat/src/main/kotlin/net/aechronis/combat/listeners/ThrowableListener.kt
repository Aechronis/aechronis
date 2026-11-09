package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Throwable
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.item.PlayerBeginItemUseEvent
import net.minestom.server.event.item.PlayerCancelItemUseEvent
import net.minestom.server.event.item.PlayerFinishItemUseEvent
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerSwapItemEvent

object ThrowableListener {
    private fun heldThrowable(player: Player): Throwable? = Combat.playerStates[player]?.heldThrowable

    private fun onBeginItemUse(event: PlayerBeginItemUseEvent) {
        val throwable = Item.getFromItemStack(event.itemStack) as? Throwable ?: return
        if (event.hand != PlayerHand.MAIN || !throwable.hold(event.player)) event.isCancelled = true
    }

    private fun onCancelItemUse(event: PlayerCancelItemUseEvent) {
        heldThrowable(event.player)?.release(event.player)
    }

    private fun onFinishItemUse(event: PlayerFinishItemUseEvent) {
        heldThrowable(event.player)?.release(event.player)
    }

    private fun onPlayerDeath(event: PlayerDeathEvent) {
        heldThrowable(event.player)?.onHolderDeath(event.player)
    }

    private fun onHeldSlotChange(event: PlayerChangeHeldSlotEvent) {
        if (heldThrowable(event.player) != null) event.isCancelled = true
    }

    private fun onSwapItem(event: PlayerSwapItemEvent) {
        if (heldThrowable(event.player) != null) event.isCancelled = true
    }

    private fun onInventoryClick(event: InventoryPreClickEvent) {
        if (heldThrowable(event.player) != null) event.isCancelled = true
    }

    private fun onItemDrop(event: ItemDropEvent) {
        if (heldThrowable(event.player) != null) event.isCancelled = true
    }

    // Saved stacks can predate the components that let the client hold them.
    private fun onInventoryItemChange(event: InventoryItemChangeEvent) {
        val throwable = Item.getFromItemStack(event.newItem) as? Throwable ?: return
        val upgraded = throwable.withHoldToThrow(event.newItem)
        if (upgraded != event.newItem) event.inventory.setItemStack(event.slot, upgraded)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerBeginItemUseEvent::class.java, ThrowableListener::onBeginItemUse)
        Combat.eventNode.addListener(PlayerCancelItemUseEvent::class.java, ThrowableListener::onCancelItemUse)
        Combat.eventNode.addListener(PlayerFinishItemUseEvent::class.java, ThrowableListener::onFinishItemUse)
        Combat.eventNode.addListener(PlayerDeathEvent::class.java, ThrowableListener::onPlayerDeath)
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, ThrowableListener::onHeldSlotChange)
        Combat.eventNode.addListener(PlayerSwapItemEvent::class.java, ThrowableListener::onSwapItem)
        Combat.eventNode.addListener(InventoryPreClickEvent::class.java, ThrowableListener::onInventoryClick)
        // Cancel before Vanilla's drop listener can spawn a reusable throwable item.
        Combat.highPriorityEventNode.addListener(ItemDropEvent::class.java, ThrowableListener::onItemDrop)
        Combat.lowPriorityEventNode.addListener(InventoryItemChangeEvent::class.java, ThrowableListener::onInventoryItemChange)
    }
}
