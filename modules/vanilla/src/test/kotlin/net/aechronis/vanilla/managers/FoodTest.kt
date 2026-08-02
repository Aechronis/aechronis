package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.objects.FoodItem
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals

class FoodTest : ManagerTest() {
    @Test
    fun `eating applies hunger and caps saturation at the new food level`() {
        val player = VanillaTest.createPlayer(Pos(80.5, 40.0, 4.5))
        player.food = 18
        player.foodSaturation = 17f

        Food.onEat(player, FoodItem(Material.COOKED_BEEF, hunger = 8, saturation = 12.8f))

        assertEquals(20, player.food)
        assertEquals(20f, player.foodSaturation)
        VanillaTest.remove(player)
    }

    @Test
    fun `exhaustion consumes saturation before hunger and keeps the remainder`() {
        val player = VanillaTest.createPlayer(Pos(82.5, 40.0, 4.5))
        player.food = 10
        player.foodSaturation = 2f

        Food.addExhaustion(player, 5f)

        assertEquals(10, player.food)
        assertEquals(1f, player.foodSaturation)

        Food.addExhaustion(player, 3f)
        assertEquals(10, player.food)
        assertEquals(0f, player.foodSaturation)

        Food.addExhaustion(player, 4f)
        assertEquals(9, player.food)
        VanillaTest.remove(player)
    }
}
