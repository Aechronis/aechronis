package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Crates
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.inventory.Inventory

object CratesListener {
    fun onHandAnimation(event: PlayerHandAnimationEvent) {
        if (event.isCancelled || event.hand != PlayerHand.MAIN) return

        val item = event.player.itemInMainHand
        if (Crates.crateFor(item) == null) return

        event.isCancelled = true
        Crates.openCrate(event.player, item)
    }

    fun onPreClick(event: InventoryPreClickEvent) {
        val inventory = event.inventory as? Inventory ?: return
        if (Crates.isCrateInventory(inventory)) event.isCancelled = true
    }

    fun onClose(event: InventoryCloseEvent) {
        val inventory = event.inventory as? Inventory ?: return
        Crates.closeInventory(event.player, inventory)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        Crates.disconnect(event.player)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerHandAnimationEvent::class.java, CratesListener::onHandAnimation)
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, CratesListener::onPreClick)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, CratesListener::onClose)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, CratesListener::onDisconnect)
    }
}
