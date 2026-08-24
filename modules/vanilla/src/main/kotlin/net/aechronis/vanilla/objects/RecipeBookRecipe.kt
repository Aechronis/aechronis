package net.aechronis.vanilla.objects

import net.minestom.server.item.Material
import net.minestom.server.recipe.Ingredient
import net.minestom.server.recipe.RecipeBookCategory
import net.minestom.server.recipe.display.RecipeDisplay
import net.minestom.server.recipe.display.SlotDisplay
import net.minestom.server.recipe.Recipe as MinestomRecipe

/** Publishes a Vanilla crafting recipe as an always-unlocked client recipe-book entry. */
class RecipeBookRecipe(
    val source: Recipe,
) : MinestomRecipe {
    /**
     * These instances must remain stable: recipe-book placement packets identify a display by an
     * opaque id, which the recipe manager resolves back to this display instance.
     */
    val displays: List<RecipeDisplay> =
        when (source) {
            is Shaped ->
                listOf(
                    RecipeDisplay.CraftingShaped(
                        source.patternWidth,
                        source.patternHeight,
                        source.pattern.map(::slotDisplay),
                        SlotDisplay.ItemStack(source.output),
                        SlotDisplay.Item(Material.CRAFTING_TABLE),
                    ),
                )
            is RecipesShapeless ->
                listOf(
                    RecipeDisplay.CraftingShapeless(
                        source.recipesIngredients.map(::slotDisplay),
                        SlotDisplay.ItemStack(source.output),
                        SlotDisplay.Item(Material.CRAFTING_TABLE),
                    ),
                )
            // Some server-only recipes (for example, unlock recipes) do not have enough
            // information to be represented by the vanilla recipe-book protocol. They must
            // remain functional without preventing every recipe from being registered.
            else -> emptyList()
        }

    override fun createRecipeDisplays(): List<RecipeDisplay> = displays

    /** Enables the client's Can Craft filter for custom recipes. */
    override fun craftingRequirements(): List<Ingredient>? =
        when (source) {
            is Shaped -> source.pattern.filterNotNull().map(::ingredient)
            is RecipesShapeless -> source.recipesIngredients.map(::ingredient)
            else -> null
        }

    override fun recipeBookCategory(): RecipeBookCategory = RecipeBookCategory.CRAFTING_MISC

    private fun slotDisplay(ingredient: RecipesIngredient?): SlotDisplay =
        when {
            ingredient == null -> SlotDisplay.Empty.INSTANCE
            ingredient.materials.size == 1 -> SlotDisplay.Item(ingredient.materials.first())
            else -> SlotDisplay.Composite(ingredient.materials.map(SlotDisplay::Item))
        }

    private fun ingredient(ingredient: RecipesIngredient): Ingredient = Ingredient(ingredient.materials.toList())
}
