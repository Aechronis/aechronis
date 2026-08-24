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

    /**
     * Moves the inputs for [recipe] from the player's inventory into this workspace.
     *
     * Existing grid contents are returned to the player's main inventory first. All mutations are
     * rolled back if that cannot be done or if the requested recipe inputs are unavailable.
     */
    fun fillFromRecipeBook(
        player: Player,
        recipe: Recipe,
        makeAll: Boolean,
    ): Boolean {
        val placements = recipePlacements(recipe) ?: return false
        val playerInventory = player.inventory
        val playerSnapshot = playerInventory.itemStacks
        val workspaceSnapshot = if (inventory === playerInventory) null else inventory.itemStacks

        fun restore() {
            updatingGrid = true
            try {
                workspaceSnapshot?.let(inventory::copyContents)
                playerInventory.copyContents(playerSnapshot)
            } finally {
                updatingGrid = false
            }
        }

        updatingGrid = true
        val placed =
            try {
                val gridItems = slots.map(inventory::getItemStack)
                slots.forEach { inventory.setItemStack(it, ItemStack.AIR) }
                inventory.setItemStack(slot, ItemStack.AIR)

                // PlayerInventory.addItemStack deliberately only uses the 36 main inventory slots,
                // so returned items cannot be placed back into the 2x2 crafting grid.
                if (gridItems.any { !it.isAir && !playerInventory.addItemStack(it) }) {
                    restore()
                    false
                } else {
                    val allocation = findAllocation(playerInventory, placements, makeAll)
                    if (allocation == null) {
                        restore()
                        false
                    } else {
                        consumeAllocation(playerInventory, allocation)
                        allocation.forEach { placement ->
                            inventory.setItemStack(
                                placement.gridSlot,
                                placement.source.withAmount(placement.amount),
                            )
                        }
                        true
                    }
                }
            } catch (_: Exception) {
                restore()
                false
            } finally {
                updatingGrid = false
            }

        refresh()
        return placed
    }

    private fun recipePlacements(recipe: Recipe): List<RecipePlacement>? =
        when (recipe) {
            is Shaped -> {
                if (recipe.patternWidth > width || recipe.patternHeight > height) return null
                buildList {
                    for (row in 0..<recipe.patternHeight) {
                        for (column in 0..<recipe.patternWidth) {
                            val ingredient = recipe.pattern[column + row * recipe.patternWidth] ?: continue
                            add(RecipePlacement(slots[column + row * width], ingredient))
                        }
                    }
                }
            }

            is RecipesShapeless -> {
                if (recipe.recipesIngredients.size > slots.size) return null
                recipe.recipesIngredients.mapIndexed { index, ingredient -> RecipePlacement(slots[index], ingredient) }
            }

            else -> null
        }

    private fun findAllocation(
        playerInventory: AbstractInventory,
        placements: List<RecipePlacement>,
        makeAll: Boolean,
    ): List<AllocatedPlacement>? {
        // The crafting grid cannot hold more than a normal stack. Trying larger batches first
        // implements the recipe-book's "make all" action without ever creating items.
        val amounts = if (makeAll) (64 downTo 1) else (1..1)
        for (amount in amounts) {
            val available =
                (0..<playerInventory.innerSize).map { index ->
                    AvailableStack(index, playerInventory.getItemStack(index), playerInventory.getItemStack(index).amount())
                }
            val allocated = ArrayList<AllocatedPlacement>(placements.size)
            var complete = true

            // Prefer constrained ingredients so an alternative ingredient does not consume the
            // only material accepted by a later, more specific ingredient.
            for (placement in placements.sortedBy { it.ingredient.materials.size }) {
                val source =
                    available
                        .asSequence()
                        .filter { availableStack ->
                            availableStack.remaining >= amount &&
                                placement.ingredient.matches(availableStack.stack) &&
                                amount <= availableStack.stack.maxStackSize()
                        }.minByOrNull { it.remaining }
                if (source == null) {
                    complete = false
                    break
                }
                source.remaining -= amount
                allocated += AllocatedPlacement(placement.gridSlot, source.slot, source.stack, amount)
            }
            if (complete) return allocated
        }
        return null
    }

    private fun consumeAllocation(
        playerInventory: AbstractInventory,
        allocation: List<AllocatedPlacement>,
    ) {
        allocation.groupBy(AllocatedPlacement::sourceSlot).forEach { (sourceSlot, placements) ->
            val source = playerInventory.getItemStack(sourceSlot)
            val amount = placements.sumOf(AllocatedPlacement::amount)
            check(source.amount() >= amount) { "Recipe-book placement consumed more items than available" }
            playerInventory.setItemStack(sourceSlot, source.consume(amount))
        }
    }

    private data class RecipePlacement(
        val gridSlot: Int,
        val ingredient: RecipesIngredient,
    )

    private data class AvailableStack(
        val slot: Int,
        val stack: ItemStack,
        var remaining: Int,
    )

    private data class AllocatedPlacement(
        val gridSlot: Int,
        val sourceSlot: Int,
        val source: ItemStack,
        val amount: Int,
    )

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

                currentStack.isSimilar(remainder) &&
                    currentStack.amount() + remainder.amount() <= currentStack.maxStackSize() -> {
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
