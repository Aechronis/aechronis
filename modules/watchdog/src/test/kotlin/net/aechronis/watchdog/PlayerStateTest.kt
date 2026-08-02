package net.aechronis.watchdog

import net.aechronis.watchdog.objects.AttackFrame
import net.aechronis.watchdog.objects.KnockbackFrame
import net.aechronis.watchdog.objects.PlayerState
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerStateTest {
    @Test
    fun `movement history retains packet and rotation evidence`() {
        val state = PlayerState(UUID.randomUUID())

        state.recordPacket(Pos(1.0, 2.0, 3.0), true, 1_000_000_000L, 90.0f, 10.0f)
        state.recordRotation(100.0f, 12.0f, true, 1_100_000_000L)

        assertEquals(2, state.packetTimesNanos.size)
        assertEquals(2, state.rotations.size)
        assertEquals(100.0f, state.rotations.last().yaw)
        assertTrue(state.clientOnGround)
    }

    @Test
    fun `duplicate attack lifecycle upgrades the same attack`() {
        val targetId = UUID.randomUUID()
        val state = PlayerState(UUID.randomUUID())
        val first = attack(targetId, confirmed = false, timestamp = 1_000L)
        val confirmed = attack(targetId, confirmed = true, timestamp = 1_050L)

        state.recordAttack(first)
        state.recordAttack(confirmed)

        assertEquals(1, state.attacks.size)
        assertTrue(state.attacks.single().confirmed)
    }

    @Test
    fun `knockback history stores expected server velocity`() {
        val state = PlayerState(UUID.randomUUID())
        val velocity = Vec(1.0, 0.5, -1.0)

        state.expectedKnockbacks.add(KnockbackFrame(4L, Pos.ZERO, velocity, "test"))

        assertEquals(velocity, state.expectedKnockbacks.single().expectedVelocity)
    }

    private fun attack(
        targetId: UUID,
        confirmed: Boolean,
        timestamp: Long,
    ) = AttackFrame(
        timestampMillis = timestamp,
        targetId = targetId,
        attackerPosition = Pos.ZERO,
        targetPosition = Pos(0.0, 0.0, 2.0),
        targetWidth = 0.6,
        targetHeight = 1.8,
        yaw = 0.0f,
        pitch = 0.0f,
        confirmed = confirmed,
    )
}
