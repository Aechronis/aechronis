package net.aechronis.vanilla.objects

import net.minestom.server.item.ItemStack

class RecipesShapeless(
    val recipesIngredients: List<RecipesIngredient>,
    val output: ItemStack,
) : Recipe {
    override fun match(recipesGrid: RecipesGrid): RecipesResult? {
        val nonEmpty = recipesGrid.nonEmptySlots()

        if (nonEmpty.size != recipesIngredients.size) return null

        val remaining = ArrayList(recipesIngredients)
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

        return RecipesResult(this, output, usage, recipesGrid)
    }
}
