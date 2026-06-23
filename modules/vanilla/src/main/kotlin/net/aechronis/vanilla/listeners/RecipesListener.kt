package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Recipes.recipes
import net.aechronis.vanilla.managers.Recipes.workspaces
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.kyori.adventure.text.Component
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.Material
import net.minestom.server.utils.inventory.PlayerInventoryUtils

object RecipesListener {
    fun onPlayerQuit(event: PlayerDisconnectEvent) {
        workspaces.remove(event.player.inventory)
    }

    fun onPlayerSpawn(event: PlayerSpawnEvent) {
        if (!event.isFirstSpawn) return
        val player = event.player
        val recipesWorkspace =
            RecipesWorkspace(
                player.inventory,
                PlayerInventoryUtils.CRAFT_RESULT,
                intArrayOf(
                    PlayerInventoryUtils.CRAFT_SLOT_1,
                    PlayerInventoryUtils.CRAFT_SLOT_2,
                    PlayerInventoryUtils.CRAFT_SLOT_3,
                    PlayerInventoryUtils.CRAFT_SLOT_4,
                ),
                2,
                2,
                recipes,
            )
        workspaces[player.inventory] = recipesWorkspace
        recipesWorkspace.refresh()
    }

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

    fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.block.registry()?.material() != Material.CRAFTING_TABLE) return

        val craftingInv =
            Inventory(
                InventoryType.CRAFTING,
                Component.translatable("container.crafting"),
            )
        val recipesWorkspace =
            RecipesWorkspace(
                craftingInv,
                0,
                intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
                3,
                3,
                recipes,
            )
        workspaces[craftingInv] = recipesWorkspace
        recipesWorkspace.refresh()
        event.player.openInventory(craftingInv)
    }

    fun onInvChange(event: InventoryItemChangeEvent) {
        val workspace = workspaces[event.inventory] ?: return
        if (workspace.updatingGrid || workspace.updatingResult) return
        if (workspace.isGridSlot(event.slot)) workspace.refresh()
    }

    fun onInvClose(event: InventoryCloseEvent) {
        val closedInv = event.inventory
        if (closedInv !is Inventory) return
        if (closedInv.inventoryType != InventoryType.CRAFTING) return

        val workspace = workspaces.remove(closedInv)
        workspace?.returnGridItems(event.player)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, RecipesListener::onInvClick)
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, RecipesListener::onInteract)
        Vanilla.eventNode.addListener(InventoryItemChangeEvent::class.java, RecipesListener::onInvChange)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, RecipesListener::onInvClose)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, RecipesListener::onPlayerQuit)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, RecipesListener::onPlayerSpawn)
    }
}
