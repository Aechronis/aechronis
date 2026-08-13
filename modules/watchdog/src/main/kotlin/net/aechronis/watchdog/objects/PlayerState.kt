package net.aechronis.watchdog.objects

import net.aechronis.watchdog.checks.CollisionSnapshot
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import java.util.UUID

class PlayerState internal constructor(
    val playerId: UUID,
) {
    internal val evidence = EvidenceTracker(playerId)
    internal var lastPosition: Pos? = null
    internal var lastAcceptedPosition: Pos? = null
    internal var clientOnGround = false
    internal var lastAcceptedOnGround = false
    internal var airTicks = 0
    internal var lastAcceptedAtNanos = 0L
    internal var lastCollision: CollisionSnapshot? = null
    internal var flightBuffer = 0.0
    internal var timerBalanceNanos = 0L
    internal var lastTimerSampleNanos = 0L
    internal var lastPacketAtNanos = 0L
    internal var packetWindowStartedAtNanos = 0L
    internal var movementPacketsInWindow = 0
    internal var lastTimerFlagWindowNanos = 0L
    internal var movementExemptUntilTick = 0L
    internal var velocityExemptUntilTick = 0L
    internal var lastAttackTarget: UUID? = null
    internal var lastAttackAtMillis = 0L
    internal val pendingPings = mutableMapOf<Int, Long>()
    internal var pingSequence = 0
    internal var pingTimeoutStrikes = 0
    internal var lastPingTimeoutTick = 0L
    internal var lastEvaluatedSwingCount = 0
    internal var lastEvaluatedAttackCount = 0
    internal val packetTimesNanos = ArrayDeque<Long>()
    internal val rotations = ArrayDeque<RotationFrame>()
    internal val swingsAtMillis = ArrayDeque<Long>()
    internal val attacks = ArrayDeque<AttackFrame>()
    internal val expectedKnockbacks = ArrayDeque<KnockbackFrame>()

    internal fun reset(position: Pos) {
        lastPosition = position
        lastAcceptedPosition = position
        clientOnGround = false
        lastAcceptedOnGround = false
        airTicks = 0
        lastAcceptedAtNanos = 0L
        lastCollision = null
        flightBuffer = 0.0
        timerBalanceNanos = 0L
        lastTimerSampleNanos = 0L
        lastPacketAtNanos = 0L
        packetWindowStartedAtNanos = 0L
        movementPacketsInWindow = 0
        lastTimerFlagWindowNanos = 0L
        pendingPings.clear()
        pingTimeoutStrikes = 0
        lastPingTimeoutTick = 0L
        packetTimesNanos.clear()
        rotations.clear()
        swingsAtMillis.clear()
        attacks.clear()
        expectedKnockbacks.clear()
        lastEvaluatedSwingCount = 0
        lastEvaluatedAttackCount = 0
    }

    internal fun recordPacket(
        position: Point,
        onGround: Boolean,
        nowNanos: Long,
        yaw: Float? = null,
        pitch: Float? = null,
    ) {
        clientOnGround = onGround
        lastPacketAtNanos = nowNanos
        packetTimesNanos.addLast(nowNanos)
        trim(packetTimesNanos, nowNanos - HISTORY_NANOS)
        if (yaw != null && pitch != null) {
            rotations.addLast(RotationFrame(nowNanos, yaw, pitch))
            while (rotations.size > 40) rotations.removeFirst()
        }
        if (packetWindowStartedAtNanos == 0L || nowNanos - packetWindowStartedAtNanos >= NANOS_PER_SECOND) {
            packetWindowStartedAtNanos = nowNanos
            movementPacketsInWindow = 0
        }
        movementPacketsInWindow++

        if (!position.x().isFinite() || !position.y().isFinite() || !position.z().isFinite()) {
            movementPacketsInWindow = Int.MAX_VALUE
        }
    }

    internal fun recordRotation(
        yaw: Float,
        pitch: Float,
        onGround: Boolean,
        nowNanos: Long,
    ) {
        clientOnGround = onGround
        lastPacketAtNanos = nowNanos
        packetTimesNanos.addLast(nowNanos)
        trim(packetTimesNanos, nowNanos - HISTORY_NANOS)
        if (packetWindowStartedAtNanos == 0L || nowNanos - packetWindowStartedAtNanos >= NANOS_PER_SECOND) {
            packetWindowStartedAtNanos = nowNanos
            movementPacketsInWindow = 0
        }
        movementPacketsInWindow++
        rotations.addLast(RotationFrame(nowNanos, yaw, pitch))
        while (rotations.size > 40) rotations.removeFirst()
    }

    internal fun recordSwing(nowMillis: Long) {
        swingsAtMillis.addLast(nowMillis)
        trim(swingsAtMillis, nowMillis - HISTORY_MILLIS)
    }

    internal fun recordAttack(attack: AttackFrame): AttackFrame {
        val previous = attacks.lastOrNull()
        if (previous != null &&
            previous.targetId == attack.targetId &&
            attack.timestampMillis - previous.timestampMillis <= 100L
        ) {
            attacks.removeLast()
        }
        attacks.addLast(attack)
        trim(attacks, attack.timestampMillis - HISTORY_MILLIS) { it.timestampMillis }
        return attack
    }

    private fun <T> trim(
        values: ArrayDeque<T>,
        cutoff: Long,
        time: (T) -> Long = { it as Long },
    ) {
        while (values.isNotEmpty() && time(values.first()) < cutoff) values.removeFirst()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val HISTORY_NANOS = 2_000_000_000L
        const val HISTORY_MILLIS = 2_000L
    }
}
