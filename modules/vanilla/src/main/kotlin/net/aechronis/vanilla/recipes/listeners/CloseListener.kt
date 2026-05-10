package net.aechronis.vanilla.recipes.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.recipes.Recipes.workspaces
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType

object CloseListener {
    fun onInvClose(event: InventoryCloseEvent) {
        val closedInv = event.inventory
        if (closedInv !is Inventory) return
        if (closedInv.inventoryType != InventoryType.CRAFTING) return

        val workspace = workspaces.remove(closedInv)
        workspace?.returnGridItems(event.player)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, CloseListener::onInvClose)
    }
}
