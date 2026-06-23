package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Recipes
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.aechronis.vanilla.utils.Command
import net.kyori.adventure.text.Component
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import kotlin.collections.set

class Craft : Command("craft", "vanilla.craft") {
    init {
        setDefaultExecutor { player, _ ->
            val craftingInv =
                Inventory(
                    InventoryType.CRAFTING,
                    Component.translatable("container.crafting"),
                )
            val workspace =
                RecipesWorkspace(
                    craftingInv,
                    0,
                    intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
                    3,
                    3,
                    Recipes.recipes,
                )
            Recipes.workspaces[craftingInv] = workspace
            workspace.refresh()
            player.openInventory(craftingInv)
        }
    }
}
