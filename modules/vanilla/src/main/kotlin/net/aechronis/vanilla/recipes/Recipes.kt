package net.aechronis.vanilla.recipes

import net.aechronis.vanilla.recipes.Listeners.listeners
import net.aechronis.vanilla.recipes.craft.Ingredient
import net.aechronis.vanilla.recipes.craft.Recipe
import net.aechronis.vanilla.recipes.craft.Shaped
import net.aechronis.vanilla.recipes.craft.Shapeless
import net.aechronis.vanilla.recipes.craft.Workspace
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Recipes {
    val recipes: MutableList<Recipe> = ArrayList()
    val workspaces: HashMap<AbstractInventory, Workspace> = HashMap()

    fun init() {
        val plank = Ingredient.of(Material.OAK_PLANKS)!!
        val log = Ingredient.of(Material.OAK_LOG)!!

        // workbench
        recipes.add(
            Shaped(
                2,
                2,
                arrayOf(plank, plank, plank, plank),
                ItemStack.of(Material.CRAFTING_TABLE),
            ),
        )

        // planks
        recipes.add(
            Shapeless(
                listOf(log),
                ItemStack.of(Material.OAK_PLANKS, 4),
            ),
        )

        listeners()
    }
}
