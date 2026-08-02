package net.aechronis.vanilla.objects

import net.minestom.server.item.ItemStack

data class RecipesResult(
    val recipe: Recipe,
    val result: ItemStack,
    val usage: HashMap<Int, Int>, // slot -> items consumed
    val recipesGrid: RecipesGrid,
)
