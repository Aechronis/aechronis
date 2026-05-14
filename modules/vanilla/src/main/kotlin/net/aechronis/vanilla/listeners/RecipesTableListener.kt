package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Recipes.recipes
import net.aechronis.vanilla.managers.Recipes.workspaces
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.kyori.adventure.text.Component
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.Material
import kotlin.collections.set

object RecipesTableListener {
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

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockInteractEvent::class.java, RecipesTableListener::onInteract)
    }
}
