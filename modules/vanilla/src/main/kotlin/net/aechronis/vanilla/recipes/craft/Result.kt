package net.aechronis.vanilla.recipes.craft

import net.aechronis.vanilla.recipes.grid.Grid
import net.minestom.server.item.ItemStack

data class Result(
    val recipe: Recipe,
    val result: ItemStack,
    val usage: HashMap<Int, Int>, // slot -> items consumed
    val grid: Grid,
)
