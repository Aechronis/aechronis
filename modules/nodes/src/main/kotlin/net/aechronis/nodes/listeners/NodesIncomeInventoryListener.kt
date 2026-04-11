package net.aechronis.nodes.listeners

import net.aechronis.nodes.Nodes
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.click.Click

object NodesIncomeInventoryListener {
    // allow removing items from town income inventory, but not putting items in
    // for simplicity, just allow all shift clicks when clicking in the town income gui, else cancel event
    private fun onInventoryClick(event: InventoryPreClickEvent) {
        // player has "town income" inv open
        if (event.player.openInventory?.size == 45) {
            if (event.inventory.size != 45) {
                event.isCancelled = true
            }
            if (!(event.click is Click.LeftShift || event.click is Click.RightShift)) {
                event.isCancelled = true
            }
        }
    }

    private fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.inventory.size == 45) {
            Nodes.onTownIncomeInventoryClose()
        }
    }

    fun init() {
        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java, this::onInventoryClick)
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java, this::onInventoryClose)
    }
}
