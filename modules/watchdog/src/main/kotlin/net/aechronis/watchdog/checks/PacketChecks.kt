package net.aechronis.watchdog.checks

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.minestom.server.entity.Player
import kotlin.math.abs

internal object PacketChecks {
    fun onPacket(
        player: Player,
        state: PlayerState,
        yaw: Float?,
        pitch: Float?,
        config: WatchdogConfig,
        flag: FlagSink,
    ) {
        if (yaw != null && (!yaw.isFinite() || abs(yaw) > MAX_ROTATION)) {
            flag(player, FlagType.BAD_PACKETS, 1.0, "invalid yaw $yaw")
        }
        if (pitch != null && (!pitch.isFinite() || pitch !in -90.0f..90.0f)) {
            flag(player, FlagType.BAD_PACKETS, 1.0, "invalid pitch $pitch")
        }

        if (state.packetTimesNanos.size > config.maxMovementPacketsPerSecond) {
            flag(
                player,
                FlagType.TIMER,
                0.4,
                "received ${state.packetTimesNanos.size} movement packets in two seconds",
            )
        }
    }

    private const val MAX_ROTATION = 360.0f * 1_000_000.0f
}
