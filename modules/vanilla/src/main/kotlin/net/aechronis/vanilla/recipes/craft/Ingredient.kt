package net.aechronis.vanilla.recipes.craft

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class Ingredient private constructor(
    val materials: Set<Material>,
) {
    fun matches(item: ItemStack): Boolean = !item.isAir && item.material() in materials

    companion object {
        fun of(material: Material): Ingredient? {
            if (material == Material.AIR) return null
            return Ingredient(setOf(material))
        }

        fun of(materials: Set<Material>): Ingredient? {
            if (materials.isEmpty()) return null
            return Ingredient(materials.toSet())
        }
    }
}
