package net.aechronis.server.craft

import net.aechronis.combat.constants.Tags
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Guns
import net.aechronis.vanilla.objects.RecipesGrid
import net.aechronis.vanilla.objects.RecipesGridSlot
import net.aechronis.vanilla.objects.RecipesShapeless
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeaponsTest {
    @BeforeTest
    fun initializeMinecraft() {
        synchronized(WeaponsTest::class) {
            if (!minecraftInitialized) {
                MinecraftServer.init()
                minecraftInitialized = true
            }
        }
    }

    @Test
    fun `gunpowder recipe uses five unstacked inputs and yields six gunpowder`() {
        val result =
            craft(
                Material.COAL,
                Material.REDSTONE,
                Material.REDSTONE,
                Material.BLAZE_POWDER,
                Material.BLAZE_POWDER,
            )

        assertEquals(Material.GUNPOWDER, result?.material())
        assertEquals(6, result?.amount())
    }

    @Test
    fun `every ammunition recipe yields four reload items`() {
        val recipes =
            listOf(
                arrayOf(Material.COPPER_INGOT, Material.COPPER_INGOT, Material.COPPER_INGOT, Material.GUNPOWDER, Material.GUNPOWDER),
                arrayOf(Material.COPPER_INGOT, Material.COPPER_INGOT, Material.GUNPOWDER),
                arrayOf(
                    Material.COPPER_INGOT,
                    Material.COPPER_INGOT,
                    Material.COPPER_INGOT,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                ),
                arrayOf(
                    Material.COPPER_INGOT,
                    Material.COPPER_INGOT,
                    Material.COPPER_INGOT,
                    Material.IRON_BLOCK,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                ),
                arrayOf(
                    Material.COPPER_INGOT,
                    Material.COPPER_INGOT,
                    Material.COPPER_INGOT,
                    Material.COPPER_INGOT,
                    Material.IRON_BLOCK,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                    Material.GUNPOWDER,
                ),
            )

        for (inputs in recipes) {
            assertEquals(4, craft(*inputs)?.amount())
        }
    }

    @Test
    fun `ak12 uses the requested block based cost and starts empty`() {
        val result =
            craft(
                Material.GOLD_BLOCK,
                Material.DIAMOND,
                Material.DIAMOND,
                Material.DIAMOND,
                Material.DIAMOND,
                Material.IRON_BLOCK,
            )

        assertEquals(Guns.ak12.name, result?.getTag(Tags.name))
        assertEquals(99, result?.get(DataComponents.DAMAGE))
    }

    @Test
    fun `no shapeless weapon recipe exceeds the three by three grid`() {
        assertTrue(
            Weapons.list
                .filterIsInstance<RecipesShapeless>()
                .all { it.recipesIngredients.size <= 9 },
        )
    }

    @Test
    fun `awp has no crafting recipe`() {
        assertTrue(
            Weapons.list
                .filterIsInstance<RecipesShapeless>()
                .none { it.output.getTag(Tags.name) == Guns.awp.name },
        )
    }

    @Test
    fun `gunpowder is required for normal rifle ammunition`() {
        assertNull(craft(Material.COPPER_INGOT, Material.COPPER_INGOT, Material.COPPER_INGOT))
        assertEquals(
            Ammo.ammo762x39mm.name,
            craft(
                Material.COPPER_INGOT,
                Material.COPPER_INGOT,
                Material.COPPER_INGOT,
                Material.GUNPOWDER,
                Material.GUNPOWDER,
            )?.getTag(Tags.name),
        )
    }

    private fun craft(vararg materials: Material): ItemStack? {
        val grid =
            RecipesGrid(
                3,
                3,
                Array(9) { slot ->
                    RecipesGridSlot(slot, materials.getOrNull(slot)?.let(ItemStack::of) ?: ItemStack.AIR)
                },
            )
        return Weapons.list.firstNotNullOfOrNull { it.match(grid) }?.result
    }

    private companion object {
        var minecraftInitialized = false
    }
}
