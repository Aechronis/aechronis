package net.aechronis.vanilla.objects

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/** [count] items of one of [materials] must sit in the slot; crafting consumes that many. */
class RecipesIngredient private constructor(
    val materials: Set<Material>,
    val count: Int = 1,
) {
    fun matches(item: ItemStack): Boolean = !item.isAir && item.material() in materials && item.amount() >= count

    fun times(count: Int): RecipesIngredient = RecipesIngredient(materials, this.count * count)

    companion object {
        fun of(material: Material): RecipesIngredient? {
            if (material == Material.AIR) return null
            return RecipesIngredient(setOf(material))
        }

        fun of(materials: Set<Material>): RecipesIngredient? {
            if (materials.isEmpty()) return null
            return RecipesIngredient(materials.toSet())
        }
    }
}
