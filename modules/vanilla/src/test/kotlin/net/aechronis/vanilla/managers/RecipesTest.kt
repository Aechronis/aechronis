package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RecipesTest : ManagerTest() {
    @Test
    fun `initialization does not duplicate configured recipes`() {
        val initial = Recipes.recipes.toList()

        Recipes.init()

        assertEquals(initial, Recipes.recipes)
    }
}
