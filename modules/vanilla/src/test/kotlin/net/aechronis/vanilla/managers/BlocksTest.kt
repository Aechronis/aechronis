package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals

class BlocksTest : ManagerTest() {
    @Test
    fun `initialization does not duplicate conversion outputs`() {
        val outputs = Blocks.outputsByInput.mapValues { it.value.toList() }

        Blocks.init()

        assertEquals(outputs, Blocks.outputsByInput.mapValues { it.value.toList() })
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
