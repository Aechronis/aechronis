package net.aechronis.vanilla.objects

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.recipe.Recipe
import net.minestom.server.recipe.RecipeBookCategory
import net.minestom.server.recipe.display.RecipeDisplay
import net.minestom.server.recipe.display.SlotDisplay

class StonecutterConversionRecipe(
    val input: Material,
    val output: Material,
) : Recipe {
    override fun createRecipeDisplays(): List<RecipeDisplay> =
        listOf(
            RecipeDisplay.Stonecutter(
                SlotDisplay.Item(input),
                SlotDisplay.ItemStack(ItemStack.of(output)),
                SlotDisplay.Item(Material.STONECUTTER),
            ),
        )

    override fun recipeBookCategory(): RecipeBookCategory = RecipeBookCategory.STONECUTTER
}
