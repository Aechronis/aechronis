package net.aechronis.vanilla.recipes.craft

import net.aechronis.vanilla.recipes.grid.Grid
import net.minestom.server.item.ItemStack

class Shapeless(
    val ingredients: List<Ingredient>,
    val output: ItemStack,
) : Recipe {
    override fun match(grid: Grid): Result? {
        val nonEmpty = grid.nonEmptySlots()

        if (nonEmpty.size != ingredients.size) return null

        val remaining = ArrayList(ingredients)
        val usage = HashMap<Int, Int>()

        for (gridSlot in nonEmpty) {
            var matched = false
            val iter = remaining.iterator()

            while (iter.hasNext()) {
                if (iter.next().matches(gridSlot.item)) {
                    iter.remove()
                    usage[gridSlot.slot] = (usage[gridSlot.slot] ?: 0) + 1
                    matched = true
                    break
                }
            }

            if (!matched) return null
        }

        if (remaining.isNotEmpty()) return null

        return Result(this, output, usage, grid)
    }
}
