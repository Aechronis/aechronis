package net.aechronis.recipes.craft

import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Ingredients {
    val list: List<Recipe> =
        listOf(
            RecipesShapeless(
                listOf(
                    RecipesIngredient.of(Material.COAL_BLOCK)!!,
                    RecipesIngredient.of(Material.REDSTONE)!!,
                    RecipesIngredient.of(Material.REDSTONE)!!,
                    RecipesIngredient.of(Material.BLAZE_POWDER)!!,
                    RecipesIngredient.of(Material.BLAZE_POWDER)!!,
                ),
                ItemStack.of(Material.GUNPOWDER, 2),
            ),
        )
}
