package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.objects.KothZone
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import java.nio.file.Files
import java.time.LocalDateTime
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
    fun `a command-created koth needs corners before it can start`() {
        val name = "koth-test-${System.nanoTime()}"
        assertTrue(Koth.add(name, 10, 60, 100.0))
        assertTrue(Files.readString(VanillaTest.pluginRoot.resolve("koth.json")).contains(name))
        assertFalse(Koth.start(name))
        assertTrue(Koth.remove(name))
    }

    @Test
    fun `schedules accept Unix cron expressions and migrate legacy times`() {
        assertEquals("30 18 * * *", Koth.normalizeSchedule("18:30"))
        assertEquals("*/15 * * * *", Koth.normalizeSchedule("*/15 * * * *"))
        assertNull(Koth.normalizeSchedule("not a cron expression"))

        val name = "koth-schedule-${System.nanoTime()}"
        assertTrue(Koth.add(name, 10, 60, 100.0))
        try {
            assertTrue(Koth.addSchedule(name, "0 18 * * *"))
            assertFalse(Koth.addSchedule(name, "0 18 * * *"))
            assertFalse(Koth.addSchedule(name, "invalid"))
            assertTrue(Koth.removeSchedule(name, "0 18 * * *"))
        } finally {
            assertTrue(Koth.remove(name))
        }
    }

    @Test
    fun `cron schedules match values ranges lists and steps`() {
        val mondayAtEighteen = LocalDateTime.of(2026, 8, 3, 18, 0)

        assertTrue(Koth.matchesSchedule("* * * * *", mondayAtEighteen))
        assertTrue(Koth.matchesSchedule("0 18 * * *", mondayAtEighteen))
        assertTrue(Koth.matchesSchedule("0 18 * * 1-5", mondayAtEighteen))
        assertTrue(Koth.matchesSchedule("0 18 * * 0,1", mondayAtEighteen))
        assertTrue(Koth.matchesSchedule("*/15 * * * *", mondayAtEighteen))
        assertFalse(Koth.matchesSchedule("1 18 * * *", mondayAtEighteen))
        assertFalse(Koth.matchesSchedule("0 18 * * 0", mondayAtEighteen))
    }

    @Test
    fun `a matching schedule starts a koth only once per minute`() {
        val name = "koth-scheduled-${System.nanoTime()}"
        val player = VanillaTest.createPlayer(Pos(0.5, 40.0, 0.5))
        val scheduledMinute = LocalDateTime.of(2026, 8, 3, 18, 0)

        assertTrue(Koth.add(name, 10, 60, 100.0))
        try {
            assertTrue(Koth.setCorner(name, player, true))
            assertTrue(Koth.setCorner(name, player, false))
            assertTrue(Koth.addSchedule(name, "0 18 3 8 *"))

            Koth.tickAt(0, scheduledMinute)
            assertTrue(Koth.isActive(name))
            assertTrue(Koth.stop(name))

            Koth.tickAt(0, scheduledMinute)
            assertFalse(Koth.isActive(name))
        } finally {
            if (Koth.isActive(name)) Koth.stop(name)
            Koth.remove(name)
            VanillaTest.remove(player)
        }
    }
}
