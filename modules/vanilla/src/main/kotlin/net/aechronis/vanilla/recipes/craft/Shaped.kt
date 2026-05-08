package net.aechronis.vanilla.recipes.craft

import net.aechronis.vanilla.recipes.grid.Grid
import net.aechronis.vanilla.recipes.grid.TrimmedGrid
import net.minestom.server.item.ItemStack

class Shaped(
    private val patternWidth: Int,
    private val patternHeight: Int,
    private val pattern: Array<Ingredient?>,
    private val output: ItemStack,
) : Recipe {
    private val requiredCount = pattern.count { it != null }

    override fun match(grid: Grid): Result? {
        val trimmed = grid.trim() ?: return null

        if (trimmed.count != requiredCount) return null
        if (trimmed.width != patternWidth || trimmed.height != patternHeight) return null

        return tryMatch(grid, trimmed, mirror = false)
            ?: tryMatch(grid, trimmed, mirror = true)
    }

    private fun tryMatch(
        grid: Grid,
        trimmed: TrimmedGrid,
        mirror: Boolean,
    ): Result? {
        val usage = HashMap<Int, Int>()

        for (row in 0..<patternHeight) {
            for (col in 0..<patternWidth) {
                val ingredientIndex =
                    if (mirror) {
                        (patternWidth - 1 - col) + row * patternWidth
                    } else {
                        col + row * patternWidth
                    }

                val ingredient = pattern[ingredientIndex]
                val gridSlot = trimmed.slot(col, row)
                val item = gridSlot.item

                if (ingredient == null) {
                    if (!item.isAir) return null
                    continue
                }

                if (!ingredient.matches(item)) return null

                usage[gridSlot.slot] = (usage[gridSlot.slot] ?: 0) + 1
            }
        }

        return Result(this, output, usage, grid)
    }
}

private fun Grid.trim(): TrimmedGrid? {
    var minCol = width
    var minRow = height
    var maxCol = -1
    var maxRow = -1
    var count = 0

    for (row in 0..<height) {
        for (col in 0..<width) {
            if (slot(col, row).item.isAir) continue
            count++
            if (col < minCol) minCol = col
            if (row < minRow) minRow = row
            if (col > maxCol) maxCol = col
            if (row > maxRow) maxRow = row
        }
    }

    if (count == 0) return null

    val trimWidth = maxCol - minCol + 1
    val trimHeight = maxRow - minRow + 1
    val trimSlots =
        Array(trimWidth * trimHeight) { i ->
            slot(minCol + (i % trimWidth), minRow + (i / trimWidth))
        }

    return TrimmedGrid(trimWidth, trimHeight, trimSlots, count)
}
