package net.aechronis.nodes.objects

import net.kyori.adventure.text.Component
import net.minestom.server.item.ItemStack

data class ActiveBuildingRecipe(
    val name: String,
    val displayName: Component,
    val input: List<ItemStack>,
    val output: List<ItemStack>,
)

data class ActiveBuildingDefinition(
    val name: String,
    val displayName: Component,
    val recipes: Map<Int, List<ActiveBuildingRecipe>>,
) {
    init {
        require(name.matches(Regex("[a-z0-9_-]+"))) { "Invalid active building name: $name" }
        require(recipes.keys == (MIN_TIER..MAX_TIER).toSet()) { "$name needs recipes for tiers 1–3" }
        recipes.values.forEach { tierRecipes ->
            require(tierRecipes.size in 1..9) { "$name needs 1–9 recipes per tier" }
            require(tierRecipes.map { it.name }.distinct().size == tierRecipes.size) { "$name has duplicate recipe names" }
            tierRecipes.forEach { recipe ->
                require(recipe.name.isNotBlank())
                require(recipe.input.isNotEmpty() && recipe.output.isNotEmpty())
                require((recipe.input + recipe.output).all { !it.isAir && it.amount() > 0 })
            }
        }
    }
}
