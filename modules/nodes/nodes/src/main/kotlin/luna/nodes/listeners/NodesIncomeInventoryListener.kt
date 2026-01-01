package luna.nodes.listeners

import luna.nodes.Nodes
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.click.Click

// allow removing items from town income inventory, but not putting items in
// for simplicity, just allow all shift clicks when clicking in the town income gui, else cancel event
public fun onInventoryClick(event: InventoryPreClickEvent) {
    val player = event.player
    val inventory = event.inventory ?: return

    val inventorySize = inventory.size
    println(inventorySize)

    // player has "town income" inv open
    if (player.openInventory?.size == 45) {
        if (event.inventory.size != 45) {
            event.isCancelled = true
        }
        if (!(event.click is Click.LeftShift && event.click is Click.RightShift)) {
            event.isCancelled = true
        }
    }
}

public fun onInventoryClose(event: InventoryCloseEvent) {
    if (event.inventory.size == 45) {
        Nodes.onTownIncomeInventoryClose()
    }
}
