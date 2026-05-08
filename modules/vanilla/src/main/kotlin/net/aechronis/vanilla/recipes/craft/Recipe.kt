package net.aechronis.vanilla.recipes.craft

import net.aechronis.vanilla.recipes.grid.Grid
import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack

interface Recipe {
    fun match(grid: Grid): Result?

    fun remainingItems(grid: Grid): List<ItemStack> = grid.slots.map { it.item.get(DataComponents.USE_REMAINDER) ?: ItemStack.AIR }
}
