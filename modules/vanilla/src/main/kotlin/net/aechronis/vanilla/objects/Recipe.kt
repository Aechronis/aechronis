package net.aechronis.vanilla.objects

import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack

interface Recipe {
    fun match(recipesGrid: RecipesGrid): RecipesResult?

    fun remainingItems(recipesGrid: RecipesGrid): List<ItemStack> =
        recipesGrid.slots.map {
            it.item.get(DataComponents.USE_REMAINDER)
                ?: ItemStack.AIR
        }
}
