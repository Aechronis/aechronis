package net.aechronis.vanilla.objects

class RecipesGrid(
    val width: Int,
    val height: Int,
    val slots: Array<RecipesGridSlot>,
) {
    fun slot(
        column: Int,
        row: Int,
    ): RecipesGridSlot = slots[column + row * width]

    fun nonEmptySlots(): List<RecipesGridSlot> = slots.filter { !it.item.isAir }
}
