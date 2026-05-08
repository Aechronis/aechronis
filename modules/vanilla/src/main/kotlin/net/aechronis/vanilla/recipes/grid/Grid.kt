package net.aechronis.vanilla.recipes.grid

import net.aechronis.vanilla.recipes.grid.GridSlot

class Grid(
    val width: Int,
    val height: Int,
    val slots: Array<GridSlot>,
) {
    fun slot(
        column: Int,
        row: Int,
    ): GridSlot = slots[column + row * width]

    fun nonEmptySlots(): List<GridSlot> = slots.filter { !it.item.isAir }
}
