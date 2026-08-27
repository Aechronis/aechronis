package net.aechronis.vanilla.objects

import net.aechronis.vanilla.config.CratesConfig
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrateTest {
    @Test
    fun `crate defaults to a chest minecart`() {
        val crate = Crate("basic", "Basic Crate", rewards = mapOf(ItemStack.of(Material.DIRT) to 1.0))

        assertEquals(Material.CHEST_MINECART, crate.material)
    }

    @Test
    fun `crate rejects invalid reward chances`() {
        val crate = Crate("basic", "Basic Crate", rewards = mapOf(ItemStack.of(Material.DIRT) to 0.0))

        assertFailsWith<IllegalArgumentException> { crate.validate() }
    }

    @Test
    fun `crate accepts more than nine rewards`() {
        val rewards = (0..11).associate { ItemStack.of(Material.DIRT, it + 1) to 1.0 }
        val crate = Crate("basic", "Basic Crate", rewards = rewards)

        crate.validate()
    }

    @Test
    fun `default vote crate has the configured rewards`() {
        val crate = CratesConfig().crates.single { it.id == "vote" }

        assertEquals("Vote Crate", crate.title)
        assertEquals(12, crate.rewards.size)
        assertEquals(100.0, crate.rewards.values.sum())
        assertEquals(2.0, crate.rewards[ItemStack.of(Material.DIAMOND_BLOCK, 64)])
        assertEquals(3.0, crate.rewards[ItemStack.of(Material.IRON_BLOCK, 64)])
        assertEquals(3.0, crate.rewards[ItemStack.of(Material.GOLD_BLOCK, 64)])
        assertEquals(2.0, crate.rewards[ItemStack.of(Material.DRAGON_BREATH, 32)])
        assertEquals(10.0, crate.rewards[ItemStack.of(Material.DIAMOND, 64)])
        assertEquals(10.0, crate.rewards[ItemStack.of(Material.IRON_INGOT, 64)])
        assertEquals(10.0, crate.rewards[ItemStack.of(Material.GOLD_INGOT, 64)])
        assertEquals(20.0, crate.rewards[ItemStack.of(Material.COOKED_BEEF, 128)])
        assertEquals(10.0, crate.rewards[ItemStack.of(Material.GUNPOWDER, 64)])
        assertEquals(10.0, crate.rewards[ItemStack.of(Material.IRON_INGOT, 32)])
        assertEquals(10.0, crate.rewards[ItemStack.of(Material.GOLD_INGOT, 32)])
        assertEquals(10.0, crate.rewards[ItemStack.of(Material.DIAMOND, 32)])
        assertTrue(crate.rewards.values.all { it > 0.0 })
    }
}
