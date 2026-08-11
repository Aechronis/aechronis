package net.aechronis.vanilla.objects

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `crate rejects more than nine rewards`() {
        val rewards = (0..9).associate { ItemStack.of(Material.DIRT, it + 1) to 1.0 }
        val crate = Crate("basic", "Basic Crate", rewards = rewards)

        assertFailsWith<IllegalArgumentException> { crate.validate() }
    }
}
