package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Storage
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.inventory.Inventory

object StorageCloseListener {
    fun onInvClose(event: InventoryCloseEvent) {
        val closed = event.inventory
        if (closed !is Inventory) return
        val key = Storage.inventoryToKey[closed] ?: return
        Storage.save(key)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, StorageCloseListener::onInvClose)
    }
}
