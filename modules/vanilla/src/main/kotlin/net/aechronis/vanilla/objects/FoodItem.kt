package net.aechronis.vanilla.objects

import net.minestom.server.item.Material

data class FoodItem(
    val material: Material,
    val hunger: Int,
    val saturation: Float,
    val canAlwaysEat: Boolean = false,
)
