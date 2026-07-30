package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.block.Block
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeFellerTest : ManagerTest() {
    @Test
    fun `connected logs are recognized as a tree only when leaves are present`() {
        val origin = BlockVec(100, 100, 4)
        VanillaTest.instance.setBlock(origin, Block.OAK_LOG)
        VanillaTest.instance.setBlock(origin.add(0, 1, 0), Block.OAK_LOG)
        VanillaTest.instance.setBlock(origin.add(1, 1, 0), Block.OAK_LEAVES)

        assertTrue(TreeFeller.isTree(origin, VanillaTest.instance, Block.OAK_LOG))

        VanillaTest.instance.setBlock(origin.add(1, 1, 0), Block.AIR)
        assertFalse(TreeFeller.isTree(origin, VanillaTest.instance, Block.OAK_LOG))
        VanillaTest.instance.setBlock(origin, Block.AIR)
        VanillaTest.instance.setBlock(origin.add(0, 1, 0), Block.AIR)
    }

    @Test
    fun `stripped logs map to their normal sapling material`() {
        assertEquals(Material.OAK_SAPLING, TreeFeller.saplingMaterial(Block.STRIPPED_OAK_LOG))
        assertEquals(Material.SPRUCE_SAPLING, TreeFeller.saplingMaterial(Block.SPRUCE_LOG))
    }
}
