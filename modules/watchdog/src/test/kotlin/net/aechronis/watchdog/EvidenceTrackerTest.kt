package net.aechronis.watchdog

import net.aechronis.watchdog.objects.EvidenceTracker
import net.aechronis.watchdog.objects.FlagType
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EvidenceTrackerTest {
    @Test
    fun `combines evidence inside the configured window`() {
        val playerId = UUID.randomUUID()
        val tracker = EvidenceTracker(playerId)
        val config = WatchdogConfig(flagThreshold = 0.75)

        assertNull(tracker.add(FlagType.SPEED, 0.4, "first", config, now = 1_000))
        val flag = assertNotNull(tracker.add(FlagType.SPEED, 0.4, "second", config, now = 1_500))

        assertEquals(playerId, flag.playerId)
        assertEquals(FlagType.SPEED, flag.type)
        assertEquals(0.8, flag.certainty)
        assertEquals("second", flag.details)
    }

    @Test
    fun `expires evidence outside the configured window`() {
        val tracker = EvidenceTracker(UUID.randomUUID())
        val config = WatchdogConfig(flagThreshold = 0.75, evidenceWindowMillis = 100)

        assertNull(tracker.add(FlagType.TIMER, 0.5, "first", config, now = 1_000))
        assertNull(tracker.add(FlagType.TIMER, 0.5, "second", config, now = 1_101))
    }

    @Test
    fun `clears evidence after reporting a flag`() {
        val tracker = EvidenceTracker(UUID.randomUUID())
        val config = WatchdogConfig(flagThreshold = 0.5)

        assertNotNull(tracker.add(FlagType.BAD_PACKETS, 0.5, "bad", config, now = 1_000))
        assertNull(tracker.add(FlagType.BAD_PACKETS, 0.4, "new", config, now = 1_001))
    }
}
