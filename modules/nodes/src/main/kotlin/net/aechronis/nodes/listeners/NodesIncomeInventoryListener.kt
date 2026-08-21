package net.aechronis.nodes.listeners

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Town
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.inventory.click.Click

object NodesIncomeInventoryListener {
    // allow removing items from town income inventory, but not putting items in
    // for simplicity, just allow all shift clicks when clicking in the town income gui, else cancel event
    private fun onInventoryClick(event: InventoryPreClickEvent) {
        val open = event.player.openInventory ?: return
        if (Town.fromIncomeInventory(open) == null) return
        if (event.inventory !== open || event.click !is Click.LeftShift && event.click !is Click.RightShift) {
            event.isCancelled = true
        }
    }

    private fun onInventoryClose(event: InventoryCloseEvent) {
        val town = Town.fromIncomeInventory(event.inventory) ?: return
        Town.onIncomeInventoryChanged(town)
    }

    private fun onInventoryChange(event: InventoryItemChangeEvent) {
        val town = Town.fromIncomeInventory(event.inventory) ?: return
        Town.onIncomeInventoryChanged(town)
    }

    private fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
        val inventory = event.player.openInventory ?: return
        val town = Town.fromIncomeInventory(inventory) ?: return
        Town.onIncomeInventoryChanged(town)
        Nodes.saveWorld(checkIfNeedsSave = true, async = false)
    }

    fun init() {
        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java, this::onInventoryClick)
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java, this::onInventoryClose)
        Nodes.eventNode.addListener(InventoryItemChangeEvent::class.java, this::onInventoryChange)
        Nodes.highPriorityEventNode.addListener(PlayerDisconnectEvent::class.java, this::onPlayerDisconnect)
    }
}
