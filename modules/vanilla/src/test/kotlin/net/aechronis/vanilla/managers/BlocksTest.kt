package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlocksTest : ManagerTest() {
    @Test
    fun `initialization does not duplicate conversion outputs`() {
        val outputs = Blocks.outputsByInput.mapValues { it.value.toList() }

        Blocks.init()

        assertEquals(outputs, Blocks.outputsByInput.mapValues { it.value.toList() })
    }

    @Test
    fun `converter accepts stacks across the entire input row`() {
        val inv = Inventory(InventoryType.CHEST_6_ROW, Component.empty())

        repeat(Blocks.INPUT_END_SLOT - Blocks.INPUT_START_SLOT + 1) {
            assertTrue(Blocks.deposit(inv, ItemStack.of(Material.STONE, 64)).isAir)
        }

        assertEquals(
            64 * 9,
            (Blocks.INPUT_START_SLOT..Blocks.INPUT_END_SLOT).sumOf { inv.getItemStack(it).amount() },
        )
        assertEquals(64, Blocks.deposit(inv, ItemStack.of(Material.STONE, 64)).amount())
    }

    @Test
    fun `converter displays outputs as soon as an input is deposited`() {
        val inv = Inventory(InventoryType.CHEST_6_ROW, Component.empty())
        val previousOutputs = Blocks.outputsByInput.put(Material.STONE, mutableListOf(Material.COBBLESTONE))

        try {
            Blocks.deposit(inv, ItemStack.of(Material.STONE))
            Blocks.refreshConverter(inv)

            assertEquals(Material.COBBLESTONE, inv.getItemStack(Blocks.OUTPUT_START_SLOT).material())
            assertEquals(Material.COBBLESTONE, Blocks.outputFor(inv, Blocks.OUTPUT_START_SLOT))
        } finally {
            if (previousOutputs == null) {
                Blocks.outputsByInput.remove(Material.STONE)
            } else {
                Blocks.outputsByInput[Material.STONE] = previousOutputs
            }
            Blocks.closeConverter(inv)
        }
    }

    @Test
    fun `converter gives the player the total of every input stack`() {
        val player = VanillaTest.createPlayer(Pos.ZERO)
        val inv = Inventory(InventoryType.CHEST_6_ROW, Component.empty())
        val previousOutputs = Blocks.outputsByInput.put(Material.STONE, mutableListOf(Material.COBBLESTONE))

        try {
            Blocks.deposit(inv, ItemStack.of(Material.STONE, 64))
            Blocks.deposit(inv, ItemStack.of(Material.STONE, 64))
            Blocks.convert(player, inv, Material.COBBLESTONE)

            assertTrue((Blocks.INPUT_START_SLOT..Blocks.INPUT_END_SLOT).all { inv.getItemStack(it).isAir })
            assertEquals(
                128,
                player.inventory.itemStacks
                    .filter { it.material() == Material.COBBLESTONE }
                    .sumOf(ItemStack::amount),
            )
        } finally {
            if (previousOutputs == null) {
                Blocks.outputsByInput.remove(Material.STONE)
            } else {
                Blocks.outputsByInput[Material.STONE] = previousOutputs
            }
            Blocks.closeConverter(inv)
            VanillaTest.remove(player)
        }
    }

    @Test
    fun `converter cycles create only intra-cycle conversions in configuration order`() {
        val outputs =
            Blocks.conversionOutputs(
                listOf(
                    listOf(Material.STONE, Material.COBBLESTONE, Material.STONE, Material.STONE_BRICKS),
                    listOf(Material.STONE, Material.COBBLESTONE),
                    listOf(Material.OAK_LOG, Material.STRIPPED_OAK_LOG),
                    listOf(Material.DIRT),
                ),
            )

        assertEquals(
            listOf(Material.COBBLESTONE, Material.STONE_BRICKS),
            outputs[Material.STONE],
        )
        assertEquals(
            listOf(Material.STONE, Material.STONE_BRICKS),
            outputs[Material.COBBLESTONE],
        )
        assertEquals(
            listOf(Material.STONE, Material.COBBLESTONE),
            outputs[Material.STONE_BRICKS],
        )
        assertEquals(listOf(Material.STRIPPED_OAK_LOG), outputs[Material.OAK_LOG])
        assertEquals(listOf(Material.OAK_LOG), outputs[Material.STRIPPED_OAK_LOG])
        assertEquals(null, outputs[Material.DIRT])
    }
}
