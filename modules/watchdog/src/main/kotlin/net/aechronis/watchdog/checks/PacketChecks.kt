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
            flag(player, FlagType.BAD_PACKETS, 1.0, "movement position was non-finite or out of range")
            reject = true
        }

        if (yaw != null && (!yaw.isFinite() || abs(yaw) > MAX_ROTATION)) {
            flag(player, FlagType.BAD_PACKETS, 1.0, "invalid yaw $yaw")
            reject = true
        }
        if (pitch != null && (!pitch.isFinite() || pitch !in -90.0f..90.0f)) {
            flag(player, FlagType.BAD_PACKETS, 1.0, "invalid pitch $pitch")
            reject = true
        }

        if (
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
            reject = true
        } else if (state.movementPacketsInWindow > config.maxMovementPacketsPerSecond) {
            reject = true
        }

        return reject
    }

    private const val MAX_ROTATION = 360.0f * 1_000_000.0f
}
