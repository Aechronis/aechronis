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
        when (recipe) {
            is Shaped ->
                listOf(
                    RecipeDisplay.CraftingShaped(
                        recipe.patternWidth,
                        recipe.patternHeight,
                        recipe.pattern.map(::slotDisplay),
                        SlotDisplay.ItemStack(recipe.output),
                        SlotDisplay.Item(Material.CRAFTING_TABLE),
                    ),
                )
            is RecipesShapeless ->
                listOf(
                    RecipeDisplay.CraftingShapeless(
                        recipe.recipesIngredients.map(::slotDisplay),
                        SlotDisplay.ItemStack(recipe.output),
                        SlotDisplay.Item(Material.CRAFTING_TABLE),
                    ),
                )
            // Some server-only recipes (for example, unlock recipes) do not have enough
            // information to be represented by the vanilla recipe-book protocol. They must
            // remain functional without preventing every recipe from being registered.
            else -> emptyList()
        }

    override fun recipeBookCategory(): RecipeBookCategory = RecipeBookCategory.CRAFTING_MISC

    private fun slotDisplay(ingredient: RecipesIngredient?): SlotDisplay =
        when {
            ingredient == null -> SlotDisplay.Empty.INSTANCE
            ingredient.materials.size == 1 -> SlotDisplay.Item(ingredient.materials.first())
            else -> SlotDisplay.Composite(ingredient.materials.map(SlotDisplay::Item))
        }
}
