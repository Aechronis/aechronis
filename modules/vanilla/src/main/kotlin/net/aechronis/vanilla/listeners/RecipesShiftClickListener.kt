package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Recipes.workspaces
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.click.Click

object RecipesShiftClickListener {
    fun onInvClick(event: InventoryPreClickEvent) {
        val click = event.click
        val slot = event.slot
        val player = event.player
        val workspace = workspaces[event.inventory] ?: return

        if (click is Click.LeftShift || click is Click.RightShift) {
            val openInv = player.openInventory
            if (openInv != null && event.inventory === player.inventory) {
                val openWorkspace = workspaces[openInv]
                if (openWorkspace != null) {
                    event.isCancelled = true
                    depositIntoGrid(player.inventory, slot, openInv, openWorkspace)
                    return
                }
            }
        }

        // clicks outside result slot - let default handle
        if (slot != workspace.slot) return

        event.isCancelled = true

        // shift craft - loop while inventory has space
        if (click is Click.LeftShift || click is Click.RightShift) {
            while (true) {
                val match = workspace.recipesResult ?: break
                if (!player.inventory.addItemStack(match.result)) break
                workspace.craft(player)
            }
            return
        }

        // normal click on result slot
        val match = workspace.recipesResult ?: return
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

    private fun depositIntoGrid(
        source: AbstractInventory,
        sourceSlot: Int,
        target: AbstractInventory,
        targetWorkspace: RecipesWorkspace,
    ) {
        var stack = source.getItemStack(sourceSlot)
        if (stack.isAir) return

        for (gridSlot in targetWorkspace.slots) {
            if (stack.isAir) break
            val current = target.getItemStack(gridSlot)
            if (current.isAir || !current.isSimilar(stack)) continue
            val maxSize = current.maxStackSize()
            if (current.amount() >= maxSize) continue
            val space = maxSize - current.amount()
            val moved = minOf(space, stack.amount())
            target.setItemStack(gridSlot, current.withAmount(current.amount() + moved))
            stack = stack.consume(moved)
        }

        for (gridSlot in targetWorkspace.slots) {
            if (stack.isAir) break
            val current = target.getItemStack(gridSlot)
            if (!current.isAir) continue
            val maxSize = stack.maxStackSize()
            val moved = minOf(maxSize, stack.amount())
            target.setItemStack(gridSlot, stack.withAmount(moved))
            stack = stack.consume(moved)
        }

        source.setItemStack(sourceSlot, stack)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, RecipesShiftClickListener::onInvClick)
    }
}
