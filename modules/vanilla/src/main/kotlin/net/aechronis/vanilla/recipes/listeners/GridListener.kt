package net.aechronis.vanilla.recipes.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.recipes.Recipes.workspaces
import net.minestom.server.event.inventory.InventoryItemChangeEvent

object GridListener {
    fun onInvChange(event: InventoryItemChangeEvent) {
        val workspace = workspaces[event.inventory] ?: return
        if (workspace.updatingGrid || workspace.updatingResult) return
        if (workspace.isGridSlot(event.slot)) workspace.refresh()
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryItemChangeEvent::class.java, GridListener::onInvChange)
    }
}
