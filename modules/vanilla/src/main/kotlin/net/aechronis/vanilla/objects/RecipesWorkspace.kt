package net.aechronis.vanilla.objects

import net.minestom.server.entity.Player
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.item.ItemStack
import kotlin.collections.iterator

class RecipesWorkspace(
    val inventory: AbstractInventory,
    val slot: Int,
    val slots: IntArray,
    val width: Int,
    val height: Int,
    val recipes: List<Recipe>,
) {
    private val slotSet: Set<Int> = slots.toHashSet()

    var updatingGrid: Boolean = false
        private set
    var updatingResult: Boolean = false
        private set
    var recipesResult: RecipesResult? = null
        private set

    fun isGridSlot(slot: Int): Boolean = slot in slotSet

    fun refresh() {
        val grid = createGrid()

        for (recipe in recipes) {
            val match = recipe.match(grid) ?: continue
            recipesResult = match
            updateResult(match.result)
            return
        }

        recipesResult = null
        updateResult(ItemStack.AIR)
    }

    private fun createGrid(): RecipesGrid {
        val tmp =
            Array(slots.size) { i ->
                val slotIndex = slots[i]
                RecipesGridSlot(slotIndex, inventory.getItemStack(slotIndex))
            }
        return RecipesGrid(width, height, tmp)
    }

    fun craft(player: Player) {
        val recipeResult = recipesResult ?: return
        recipesResult = null
        updatingGrid = true

        try {
            consumeInputs(recipeResult)
            handleRemainders(player, recipeResult)
        } finally {
            updatingGrid = false
        }

        refresh()
    }

    private fun consumeInputs(recipesResult: RecipesResult) {
        for ((index, amount) in recipesResult.usage) {
            if (amount <= 0) continue
            val slotItem = inventory.getItemStack(index)
            inventory.setItemStack(index, slotItem.consume(amount))
        }
    }

    private fun handleRemainders(
        player: Player,
        recipeMatch: RecipesResult,
    ) {
        val remainders = recipeMatch.recipe.remainingItems(recipeMatch.recipesGrid)
        val gridSlots = recipeMatch.recipesGrid.slots

        for (i in gridSlots.indices) {
            val remainder = remainders[i]
            if (remainder.isAir) continue

            val gridSlot = gridSlots[i]
            val currentStack = inventory.getItemStack(gridSlot.slot)

            when {
                currentStack.isAir -> {
                    inventory.setItemStack(gridSlot.slot, remainder)
                }

                currentStack.isSimilar(remainder) -> {
                    inventory.setItemStack(
                        gridSlot.slot,
                        currentStack.withAmount(currentStack.amount() + remainder.amount()),
                    )
                }

                !player.inventory.addItemStack(remainder) -> {
                    player.dropItem(remainder)
                }
            }
        }
    }

    private fun updateResult(stack: ItemStack) {
        updatingResult = true
        try {
            inventory.setItemStack(slot, stack)
        } finally {
            updatingResult = false
        }
    }

    fun returnGridItems(player: Player) {
        updatingGrid = true
        try {
            for (index in slots) {
                val gridItem = inventory.getItemStack(index)
                if (gridItem.isAir) continue

                inventory.setItemStack(index, ItemStack.AIR)
                if (!player.inventory.addItemStack(gridItem)) {
                    player.dropItem(gridItem)
                }
            }
            inventory.setItemStack(slot, ItemStack.AIR)
        } finally {
            updatingGrid = false
        }
    }
}
