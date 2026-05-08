package net.aechronis.vanilla.recipes

import net.aechronis.vanilla.recipes.Recipes.recipes
import net.aechronis.vanilla.recipes.Recipes.workspaces
import net.aechronis.vanilla.recipes.craft.Workspace
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.Material
import kotlin.collections.set

object Listeners {
    fun listeners() {
        val eventHandler = MinecraftServer.getGlobalEventHandler()

        eventHandler.addListener(InventoryCloseEvent::class.java) { event ->
            val closedInv = event.inventory
            if (closedInv !is Inventory) return@addListener
            if (closedInv.inventoryType != InventoryType.CRAFTING) return@addListener

            val workspace = workspaces.remove(closedInv)
            workspace?.returnGridItems(event.player)
        }

        eventHandler.addListener(InventoryItemChangeEvent::class.java) { event ->
            val workspace = workspaces[event.inventory] ?: return@addListener
            if (workspace.updatingGrid || workspace.updatingResult) return@addListener
            if (workspace.isGridSlot(event.slot)) workspace.refresh()
        }

        eventHandler.addListener(PlayerDisconnectEvent::class.java) { event ->
            workspaces.remove(event.player.inventory)
        }

        eventHandler.addListener(PlayerSpawnEvent::class.java) { event ->
            if (!event.isFirstSpawn) return@addListener
            val player = event.player
            val workspace =
                Workspace(
                    player.inventory,
                    player,
                    0,
                    intArrayOf(1, 2, 3, 4),
                    2,
                    2,
                    recipes,
                )
            workspaces[player.inventory] = workspace
            workspace.refresh()
        }

        eventHandler.addListener(InventoryPreClickEvent::class.java) { event ->
            val click = event.click
            val slot = event.slot
            val player = event.player
            val workspace = workspaces[event.inventory]

            // shift click in player inventory while crafting table is open
            if (workspace == null) {
                if (click !is Click.LeftShift && click !is Click.RightShift) return@addListener
                val openInv = player.openInventory ?: return@addListener
                workspaces[openInv] ?: return@addListener
                event.isCancelled = true
                val button = if (click is Click.RightShift) 1 else 0
                player.inventory.shiftClick(player, slot, button)
                return@addListener
            }

            // clicks outside result slot - let default handle
            if (slot != workspace.slot) return@addListener

            event.isCancelled = true

            // shift craft - loop while inventory has space
            if (click is Click.LeftShift || click is Click.RightShift) {
                while (true) {
                    val match = workspace.result ?: break
                    if (!player.inventory.addItemStack(match.result)) break
                    workspace.craft(player)
                }
                return@addListener
            }

            // normal click on result slot
            val match = workspace.result ?: return@addListener
            val resultStack = match.result
            if (resultStack.isAir) return@addListener

            val cursor = player.inventory.cursorItem
            val pickupAmount = if (click is Click.Right) 1 else resultStack.amount()

            if (pickupAmount <= 0) return@addListener
            if (!cursor.isAir &&
                (
                    !cursor.isSimilar(resultStack) ||
                        cursor.amount() + pickupAmount > resultStack.maxStackSize()
                )
            ) {
                return@addListener
            }

            player.inventory.cursorItem =
                if (cursor.isAir) {
                    resultStack.withAmount(pickupAmount)
                } else {
                    cursor.withAmount(cursor.amount() + pickupAmount)
                }

            workspace.craft(player)
        }

        eventHandler.addListener(PlayerBlockInteractEvent::class.java) { event ->
            if (event.block.registry()?.material() != Material.CRAFTING_TABLE) return@addListener

            val craftingInv =
                Inventory(
                    InventoryType.CRAFTING,
                    Component.translatable("container.crafting"),
                )
            val workspace =
                Workspace(
                    craftingInv,
                    event.player,
                    0,
                    intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
                    3,
                    3,
                    recipes,
                )
            workspaces[craftingInv] = workspace
            workspace.refresh()
            event.player.openInventory(craftingInv)
        }
    }
}
