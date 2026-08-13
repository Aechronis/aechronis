package net.aechronis.watchdog.checks

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import kotlin.math.abs

internal object PacketChecks {
    fun onPacket(
        player: Player,
        state: PlayerState,
        position: Point?,
        yaw: Float?,
        pitch: Float?,
        config: WatchdogConfig,
        flag: FlagSink,
    ): Boolean {
        var reject = false
        val badPacketsEnabled = FlagType.BAD_PACKETS in config.enabledChecks
        val timerEnabled = FlagType.TIMER in config.enabledChecks

        if (
            position != null &&
            (
                !position.x().isFinite() ||
                    !position.y().isFinite() ||
                    !position.z().isFinite() ||
                    abs(position.x()) > Entity.MAX_COORDINATE ||
                    abs(position.y()) > Entity.MAX_COORDINATE ||
                    abs(position.z()) > Entity.MAX_COORDINATE
            )
        ) {
            if (badPacketsEnabled) {
                flag(player, FlagType.BAD_PACKETS, 1.0, "movement position was non-finite or out of range")
                reject = true
            }
        }

        if (yaw != null && (!yaw.isFinite() || abs(yaw) > MAX_ROTATION)) {
            if (badPacketsEnabled) {
                flag(player, FlagType.BAD_PACKETS, 1.0, "invalid yaw $yaw")
                reject = true
            }
        }
        if (pitch != null && (!pitch.isFinite() || pitch !in -90.0f..90.0f)) {
            if (badPacketsEnabled) {
                flag(player, FlagType.BAD_PACKETS, 1.0, "invalid pitch $pitch")
                reject = true
            }
        }

        if (position != null) {
            if (state.lastTimerSampleNanos == 0L) {
                state.lastTimerSampleNanos = state.lastPacketAtNanos
            } else {
                val elapsed = (state.lastPacketAtNanos - state.lastTimerSampleNanos).coerceAtLeast(0L)
                state.timerBalanceNanos = (state.timerBalanceNanos - elapsed).coerceAtLeast(-TIMER_DRIFT_NANOS) + TICK_NANOS
                state.lastTimerSampleNanos = state.lastPacketAtNanos
            }
        }

        if (
            timerEnabled &&
            state.movementPacketsInWindow > config.maxMovementPacketsPerSecond &&
            state.lastTimerFlagWindowNanos != state.packetWindowStartedAtNanos
        ) {
            state.lastTimerFlagWindowNanos = state.packetWindowStartedAtNanos
            val excess = state.movementPacketsInWindow - config.maxMovementPacketsPerSecond
            flag(
                player,
                FlagType.TIMER,
                (excess.toDouble() / config.maxMovementPacketsPerSecond).coerceIn(0.25, 0.6),
                "received ${state.movementPacketsInWindow} movement packets in one second",
            )
        }

        if (timerEnabled && state.timerBalanceNanos > TIMER_FLAG_THRESHOLD_NANOS) {
            flag(player, FlagType.TIMER, 0.25, "client movement clock gained ${state.timerBalanceNanos / 1_000_000}ms")
            state.timerBalanceNanos -= TICK_NANOS
        }

        if (timerEnabled && state.movementPacketsInWindow > config.maxMovementPacketsPerSecond * 2) {
            reject = true
        }

        return reject
    }

    private const val MAX_ROTATION = 360.0f * 1_000_000.0f
    private const val TICK_NANOS = 50_000_000L
    private const val TIMER_DRIFT_NANOS = 120_000_000L
    private const val TIMER_FLAG_THRESHOLD_NANOS = 250_000_000L
}
