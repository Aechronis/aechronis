package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.objects.KothConfig
import net.aechronis.vanilla.objects.KothZone
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KothTest : ManagerTest() {
    @Test
    fun `zone normalizes both corners and includes all configured blocks`() {
        val zone = KothZone(BlockVec(10, 20, 30), BlockVec(8, 18, 28))

        assertTrue(zone.contains(Pos(8.0, 18.0, 28.0)))
        assertTrue(zone.contains(Pos(10.99, 20.99, 30.99)))
        assertFalse(zone.contains(Pos(7.99, 19.0, 29.0)))
        assertFalse(zone.contains(Pos(9.0, 21.0, 29.0)))
    }

    @Test
    fun `capture state can begin and be reset`() {
        val config = KothConfig("test", VanillaTest.instance, BlockVec(0, 40, 0), BlockVec(2, 42, 2), 10)
        val state = Koth.ActiveKoth(config, 100L, 10_100L)
        val player = VanillaTest.createPlayer(Pos(0.5, 40.0, 0.5))

        Koth.beginCapture(state, player.uuid, 1_000L)
        assertEquals(player.uuid, state.capturer)
        assertEquals(1_000L, state.captureStartedAt)
        Koth.resetCapture(state)
        assertNull(state.capturer)
        assertNull(state.captureStartedAt)
        VanillaTest.remove(player)
    }
}
