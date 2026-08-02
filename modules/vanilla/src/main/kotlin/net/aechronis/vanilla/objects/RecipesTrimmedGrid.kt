package net.aechronis.vanilla.objects

class RecipesTrimmedGrid(
    val width: Int,
    val height: Int,
    val slots: Array<RecipesGridSlot>,
    val count: Int,
) {
    fun slot(
        column: Int,
        row: Int,
    ): RecipesGridSlot = slots[column + row * width]
}
