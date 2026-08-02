package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.objects.CropType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropsTest : ManagerTest() {
    @Test
    fun `initialization configures a positive duration for every crop`() {
        assertTrue(Vanilla.config.wheatMsPerStage > 0)
        assertTrue(Crops.msPerState.values.all { it > 0 })
        assertEquals(CropType.ALL.toSet(), Crops.msPerState.keys)
    }
}
