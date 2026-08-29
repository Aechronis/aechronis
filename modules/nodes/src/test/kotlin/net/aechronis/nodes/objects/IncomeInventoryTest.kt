package net.aechronis.nodes.objects

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncomeInventoryTest {
    @Test
    fun `opening income keeps its authoritative persisted total`() {
        val income = IncomeInventory()
        income.add(Material.DIAMOND, 100)

        val inventory = income.getInventory()

        assertEquals(100, income.storage[Material.DIAMOND])
        assertEquals(100, income.snapshot()[Material.DIAMOND])
        assertFalse(inventory.getItemStack(0).isAir)
    }

    @Test
    fun `withdrawals update persisted total and newly added income remains visible`() {
        val income = IncomeInventory()
        income.add(Material.DIAMOND, 100)
        val inventory = income.getInventory()

        inventory.setItemStack(0, ItemStack.AIR)
        assertTrue(income.synchronizeFromInventory())
        assertEquals(36, income.snapshot()[Material.DIAMOND])

        income.add(Material.DIAMOND, 10)
        assertEquals(46, income.snapshot()[Material.DIAMOND])
        assertEquals(46, inventory.itemStacks.sumOf { stack -> if (stack.material() == Material.DIAMOND) stack.amount() else 0 })
    }

    @Test
    fun `income beyond gui capacity stays in authoritative storage`() {
        val income = IncomeInventory()
        income.add(Material.DIAMOND, 3_000)
        val inventory = income.getInventory()

        assertEquals(3_000, income.snapshot()[Material.DIAMOND])
        assertEquals(2_880, inventory.itemStacks.sumOf(ItemStack::amount))

        inventory.setItemStack(0, ItemStack.AIR)
        assertTrue(income.synchronizeFromInventory())
        income.getInventory()

        assertEquals(2_936, income.snapshot()[Material.DIAMOND])
        assertEquals(2_880, inventory.itemStacks.sumOf(ItemStack::amount))
    }
}
