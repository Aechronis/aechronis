package net.aechronis.vanilla.recipes.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.recipes.Recipes.workspaces
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.click.Click

object ShiftClickListener {
    fun onInvClick(event: InventoryPreClickEvent) {
        val click = event.click
        val slot = event.slot
        val player = event.player
        val workspace = workspaces[event.inventory]

        // shift click in player inventory while crafting table is open
        if (workspace == null) {
            if (click !is Click.LeftShift && click !is Click.RightShift) return
            val openInv = player.openInventory ?: return
            workspaces[openInv] ?: return
            event.isCancelled = true
            val button = if (click is Click.RightShift) 1 else 0
            player.inventory.shiftClick(player, slot, button)
            return
        }

        // clicks outside result slot - let default handle
        if (slot != workspace.slot) return

        event.isCancelled = true

        // shift craft - loop while inventory has space
        if (click is Click.LeftShift || click is Click.RightShift) {
            while (true) {
                val match = workspace.result ?: break
                if (!player.inventory.addItemStack(match.result)) break
                workspace.craft(player)
            }
            return
        }

        // normal click on result slot
        val match = workspace.result ?: return
        val resultStack = match.result
        if (resultStack.isAir) return

        val cursor = player.inventory.cursorItem
        val pickupAmount = if (click is Click.Right) 1 else resultStack.amount()

        if (pickupAmount <= 0) return
        if (!cursor.isAir &&
            (
                !cursor.isSimilar(resultStack) ||
                    cursor.amount() + pickupAmount > resultStack.maxStackSize()
            )
        ) {
            return
        }

        player.inventory.cursorItem =
            if (cursor.isAir) {
                resultStack.withAmount(pickupAmount)
            } else {
                cursor.withAmount(cursor.amount() + pickupAmount)
            }

        workspace.craft(player)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, ShiftClickListener::onInvClick)
    }
}
