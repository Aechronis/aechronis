package net.aechronis.vanilla.objects

import net.minestom.server.item.Material
import net.minestom.server.recipe.RecipeBookCategory
import net.minestom.server.recipe.display.RecipeDisplay
import net.minestom.server.recipe.display.SlotDisplay
import net.minestom.server.recipe.Recipe as MinestomRecipe

/** Publishes a Vanilla crafting recipe as an always-unlocked client recipe-book entry. */
class RecipeBookRecipe(
    private val recipe: Recipe,
) : MinestomRecipe {
    override fun createRecipeDisplays(): List<RecipeDisplay> =
        listOf(
            when (recipe) {
                is Shaped ->
                    RecipeDisplay.CraftingShaped(
                        recipe.patternWidth,
                        recipe.patternHeight,
                        recipe.pattern.map(::slotDisplay),
                        SlotDisplay.ItemStack(recipe.output),
                        SlotDisplay.Item(Material.CRAFTING_TABLE),
                    )
                is RecipesShapeless ->
                    RecipeDisplay.CraftingShapeless(
                        recipe.recipesIngredients.map(::slotDisplay),
                        SlotDisplay.ItemStack(recipe.output),
                        SlotDisplay.Item(Material.CRAFTING_TABLE),
                    )
                else -> error("Recipe book display is not implemented for ${recipe::class.qualifiedName}")
            },
        )

    override fun recipeBookCategory(): RecipeBookCategory = RecipeBookCategory.CRAFTING_MISC

    private fun slotDisplay(ingredient: RecipesIngredient?): SlotDisplay =
        when {
            ingredient == null -> SlotDisplay.Empty.INSTANCE
            ingredient.materials.size == 1 -> SlotDisplay.Item(ingredient.materials.first())
            else -> SlotDisplay.Composite(ingredient.materials.map(SlotDisplay::Item))
        }
}
