package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.CropType
import net.aechronis.vanilla.objects.CropsPlantedCrop
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.block.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CropsTest : ManagerTest() {
    @Test
    fun `malformed growth snapshot fails restoration`() {
        assertFailsWith<Exception> { Crops.restoreTransientState(byteArrayOf(1)) }
    }

    @Test
    fun `growth state survives a module generation handoff`() {
        val position = Vec(96.0, 40.0, 12.0)
        val key = BlockKey(VanillaTest.instance, position)
        val planted = CropsPlantedCrop(CropType.Wheat, plantedAt = 123_456L, initialAge = 2)
        VanillaTest.instance.setBlock(position, Block.WHEAT.withProperty("age", "2"))

        try {
            Crops.crops[key] = planted
            val snapshot = Crops.captureTransientState()
            Crops.crops.clear()

            Crops.restoreTransientState(snapshot)

            assertEquals(planted, Crops.crops[key])
        } finally {
            Crops.crops.remove(key)
            VanillaTest.instance.setBlock(position, Block.AIR)
        }
    }

    @Test
    fun `initialization configures a positive duration for every crop`() {
        assertTrue(Vanilla.config.wheatMsPerStage > 0)
        assertTrue(Crops.msPerState.values.all { it > 0 })
        assertEquals(CropType.ALL.toSet(), Crops.msPerState.keys)
    }
}
