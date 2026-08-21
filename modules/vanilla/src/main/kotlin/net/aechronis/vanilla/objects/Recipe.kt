package net.aechronis.vanilla.objects

import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack

interface Recipe {
    fun match(recipesGrid: RecipesGrid): RecipesResult?

    /** Whether [player] may receive this recipe's result. */
    fun canCraft(player: Player): Boolean = true

    /** Whether the crafting listener should place [RecipesResult.result] in the player's inventory. */
    fun grantsItem(): Boolean = true

    /** Invoked after the recipe inputs have been consumed. */
    fun onCraft(player: Player) = Unit

    fun remainingItems(recipesGrid: RecipesGrid): List<ItemStack> =
        recipesGrid.slots.map {
            it.item.get(DataComponents.USE_REMAINDER)
                ?: ItemStack.AIR
        }
}
