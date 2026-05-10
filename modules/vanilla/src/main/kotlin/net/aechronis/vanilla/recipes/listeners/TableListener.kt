package net.aechronis.vanilla.recipes.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.recipes.Recipes.recipes
import net.aechronis.vanilla.recipes.Recipes.workspaces
import net.aechronis.vanilla.recipes.craft.Workspace
import net.kyori.adventure.text.Component
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.Material
import kotlin.collections.set

object TableListener {
    fun onInteract(event: PlayerBlockInteractEvent) {
        if (event.block.registry()?.material() != Material.CRAFTING_TABLE) return

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

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, TableListener::onInteract)
    }
}
