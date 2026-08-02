package net.aechronis.vanilla.objects

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class RecipesIngredient private constructor(
    val materials: Set<Material>,
) {
    fun matches(item: ItemStack): Boolean = !item.isAir && item.material() in materials

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
