package net.aechronis.combat

import net.aechronis.combat.utils.HistoricalHitbox
import net.aechronis.combat.utils.HitboxSnapshot
import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.distanceSquaredToSegment
import net.aechronis.combat.utils.interpolateHitbox
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LagCompensationTest {
    @Test
    fun `hitbox history interpolates between server ticks`() {
        val snapshots =
            listOf(
                HitboxSnapshot(100L, HistoricalHitbox(Vec(0.0, 0.0, 0.0), Vec(1.0, 2.0, 1.0))),
                HitboxSnapshot(200L, HistoricalHitbox(Vec(2.0, 0.0, 0.0), Vec(3.0, 2.0, 1.0))),
            )

        val hitbox = assertNotNull(interpolateHitbox(snapshots, 150L))

        assertEquals(Vec(1.0, 0.0, 0.0), hitbox.minimum)
        assertEquals(Vec(2.0, 2.0, 1.0), hitbox.maximum)
    }

    @Test
    fun `history does not use snapshots older than a continuity boundary`() {
        val snapshots =
            listOf(
                HitboxSnapshot(100L, HistoricalHitbox(Vec.ZERO, Vec(1.0, 2.0, 1.0))),
                HitboxSnapshot(200L, HistoricalHitbox(Vec(1.0, 0.0, 0.0), Vec(2.0, 2.0, 1.0))),
            )

        assertNull(interpolateHitbox(snapshots, 99L))
    }

    @Test
    fun `rewound box can be hit when current box misses`() {
        val ray = Ray(Pos(0.0, 1.0, 0.0), Vec(10.0, 0.0, 0.0))

        val historicalHit = ray.hitBox(Vec(4.0, 0.0, -0.5), Vec(5.0, 2.0, 0.5), "historical")
        val currentHit = ray.hitBox(Vec(4.0, 0.0, 2.0), Vec(5.0, 2.0, 3.0), "current")

        assertNotNull(historicalHit)
        assertEquals(4.0, historicalHit.t, 0.0001)
        assertNull(currentHit)
    }

    @Test
    fun `rewind includes display latency and is capped`() {
        assertEquals(100.0, LagCompensation.calculateRewindMillis(0.0))
        assertEquals(175.0, LagCompensation.calculateRewindMillis(75.0))
        assertEquals(200.0, LagCompensation.calculateRewindMillis(500.0))
    }

    @Test
    fun `trail viewer distance is measured from the entire segment`() {
        val from = Pos(0.0, 0.0, 0.0)
        val to = Pos(100.0, 0.0, 0.0)

        assertEquals(25.0, distanceSquaredToSegment(Pos(50.0, 5.0, 0.0), from, to), 0.0001)
        assertEquals(125.0, distanceSquaredToSegment(Pos(110.0, 5.0, 0.0), from, to), 0.0001)
    }
}
