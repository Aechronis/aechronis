package net.aechronis.recipes.craft

import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Food {
    val list: List<Recipe> =
        listOf(
            RecipesShapeless(
                List(3) { RecipesIngredient.of(Material.WHEAT)!! },
                ItemStack.of(Material.BREAD, 3),
            ),
        )
}
