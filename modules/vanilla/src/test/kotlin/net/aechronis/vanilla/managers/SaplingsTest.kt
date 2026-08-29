package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.SaplingType
import net.aechronis.vanilla.objects.SaplingsPlanted
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaplingsTest : ManagerTest() {
    @Test
    fun `malformed growth snapshot fails restoration`() {
        assertFailsWith<Exception> { Saplings.restoreTransientState(byteArrayOf(1)) }
    }

    @Test
    fun `growth state survives a module generation handoff`() {
        val pos = Vec(100.0, 100.0, 4.0)
        val key = BlockKey(VanillaTest.instance, pos)
        val planted = SaplingsPlanted(SaplingType.Spruce, plantedAt = 234_567L, boneMeal = 3)
        VanillaTest.instance.setBlock(pos, SaplingType.Spruce.saplingBlock)

        try {
            Saplings.saplings[key] = planted
            val snapshot = Saplings.captureTransientState()
            Saplings.saplings.clear()

            Saplings.restoreTransientState(snapshot)

            assertEquals(planted, Saplings.saplings[key])
        } finally {
            Saplings.saplings.remove(key)
            VanillaTest.instance.setBlock(pos, Block.AIR)
        }
    }

    @Test
    fun `normal sapling grows when clearance is available`() {
        val pos = Vec(104.0, 100.0, 4.0)
        val key = BlockKey(VanillaTest.instance, pos)
        VanillaTest.instance.setBlock(pos, SaplingType.Oak.saplingBlock)

        assertTrue(Saplings.grow(key, SaplingsPlanted(SaplingType.Oak, System.currentTimeMillis(), 0)))
        assertEquals(SaplingType.Oak.logBlock, VanillaTest.instance.getBlock(pos))
        VanillaTest.instance.setBlock(pos, Block.AIR)
    }
}
