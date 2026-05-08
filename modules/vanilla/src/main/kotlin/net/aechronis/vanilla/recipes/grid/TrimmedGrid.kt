package net.aechronis.vanilla.recipes.grid

class TrimmedGrid(
    val width: Int,
    val height: Int,
    val slots: Array<GridSlot>,
    val count: Int,
) {
    fun slot(
        column: Int,
        row: Int,
    ): GridSlot = slots[column + row * width]
}
