package net.aechronis.vanilla.listeners

import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import kotlin.test.Test
import kotlin.test.assertEquals

class CropsPlantListenerTest {
    @Test
    fun `hoes create hydrated farmland from grass blocks and dirt`() {
        for (block in listOf(Block.GRASS_BLOCK, Block.DIRT)) {
            val farmland = CropsPlantListener.hoeResult(block, Block.AIR, BlockFace.TOP)

            assertEquals("7", farmland?.getProperty("moisture"))
        }
    }
}
