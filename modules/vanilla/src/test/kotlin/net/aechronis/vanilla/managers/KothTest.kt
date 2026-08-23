package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.objects.KothZone
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.CommandResult
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
    fun `reward commands are normalized before persistence`() {
        val name = "koth-reward-${System.nanoTime()}"
        assertTrue(Koth.add(name, 10, 60, 100.0))
        try {
            assertTrue(Koth.addReward(name, "  /give %player% diamond 1  "))
            assertEquals(listOf("give %player% diamond 1"), Koth.rewards(name))
        } finally {
            Koth.remove(name)
        }
    }

    @Test
    fun `schedules accept Unix cron expressions and migrate legacy times`() {
        assertEquals("30 18 * * *", Koth.normalizeSchedule("18:30"))
        assertEquals("*/15 * * * *", Koth.normalizeSchedule("*/15 * * * *"))
        assertEquals("0 */3 * * *", Koth.normalizeSchedule("0 */3 * * *"))
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
        assertTrue(Koth.matchesSchedule("0 */3 * * *", LocalDateTime.of(2026, 8, 3, 3, 0)))
        assertFalse(Koth.matchesSchedule("0 */3 * * *", LocalDateTime.of(2026, 8, 3, 4, 0)))
        assertFalse(Koth.matchesSchedule("1 18 * * *", mondayAtEighteen))
        assertFalse(Koth.matchesSchedule("0 18 * * 0", mondayAtEighteen))
    }

    @Test
    fun `schedule command accepts five cron fields including hour steps`() {
        val name = "koth-command-schedule-${System.nanoTime()}"
        val player = VanillaTest.createPlayer(Pos(0.5, 40.0, 0.5))
        val permissionFlag = "aechronis.dangerously-enable-all-permissions"
        val previousPermissionFlag = System.getProperty(permissionFlag)
        System.setProperty(permissionFlag, "true")

        assertTrue(Koth.add(name, 10, 60, 100.0))
        try {
            val result =
                MinecraftServer
                    .getCommandManager()
                    .execute(player, "koth schedule add $name 0 */3 * * *")

            assertEquals(CommandResult.Type.SUCCESS, result.type)
            assertEquals(listOf("0 */3 * * *"), Koth.schedules(name))
        } finally {
            if (previousPermissionFlag == null) {
                System.clearProperty(permissionFlag)
            } else {
                System.setProperty(permissionFlag, previousPermissionFlag)
            }
            Koth.remove(name)
            VanillaTest.remove(player)
        }
    }

    @Test
    fun `a capturer glows until capture is reset`() {
        val name = "koth-glow-${System.nanoTime()}"
        val player = VanillaTest.createPlayer(Pos(0.5, 40.0, 0.5))
        player.isGlowing = false

        assertTrue(Koth.add(name, 10, 60, 1.0))
        try {
            assertTrue(Koth.setCorner(name, player, true))
            assertTrue(Koth.setCorner(name, player, false))
            assertTrue(Koth.start(name))

            val now = System.currentTimeMillis()
            val state = Koth.active[name]!!
            Koth.beginCapture(state, player, now)
            Koth.updateBossBars(now)

            assertTrue(state.capturer == player.uuid)
            assertTrue(player.isGlowing)

            Koth.resetCaptures(player.uuid)
            assertFalse(player.isGlowing)
        } finally {
            if (Koth.isActive(name)) Koth.stop(name)
            Koth.remove(name)
            VanillaTest.remove(player)
        }
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
