package net.aechronis.watchdog.checks

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.aechronis.watchdog.objects.PlayerStateReg
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import kotlin.math.abs
import kotlin.math.hypot

internal object MovementChecks {
    fun onMove(
        player: Player,
        state: PlayerState,
        position: Pos,
        onGround: Boolean,
        config: WatchdogConfig,
        flag: FlagSink,
    ) {
        if (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()) {
            flag(player, FlagType.BAD_PACKETS, 1.0, "non-finite movement position")
            return
        }

        val nowNanos = System.nanoTime()
        if (state.lastPacketAtNanos == 0L || nowNanos - state.lastPacketAtNanos > MAX_CLIENT_MOVE_AGE_NANOS) {
            state.lastAcceptedPosition = position
            state.lastAcceptedAtNanos = nowNanos
            return
        }

        val previous = state.lastAcceptedPosition
        val previousAtNanos = state.lastAcceptedAtNanos
        val previousOnGround = state.lastAcceptedOnGround
        val collision = CollisionOracle.sample(player, position)
        state.lastAcceptedPosition = position
        state.lastAcceptedOnGround = onGround
        state.lastAcceptedAtNanos = nowNanos

        if (onGround) state.airTicks = 0 else state.airTicks++
        if (previous == null || previousAtNanos == 0L || isExempt(player, state)) return

        val elapsedTicks = ((nowNanos - previousAtNanos) / TICK_NANOS).coerceAtLeast(1L)
        val horizontalDistance = hypot(position.x - previous.x, position.z - previous.z)
        val horizontalLimit = config.maxHorizontalMovePerTick * elapsedTicks * 1.25
        if (horizontalDistance > horizontalLimit) {
            flag(
                player,
                FlagType.SPEED,
                certainty(horizontalDistance, horizontalLimit),
                "moved $horizontalDistance blocks horizontally over $elapsedTicks ticks",
            )
        }

        val verticalDistance = position.y - previous.y
        if (verticalDistance > config.maxUpwardMovePerTick * elapsedTicks && !onGround) {
            flag(
                player,
                FlagType.FLIGHT,
                certainty(verticalDistance, config.maxUpwardMovePerTick * elapsedTicks),
                "moved $verticalDistance blocks upward while airborne",
            )
        }
        if (!onGround && state.airTicks >= 15 && abs(verticalDistance) < 0.01) {
            flag(player, FlagType.FLIGHT, 0.35, "hovered for ${state.airTicks} air ticks")
        }

        if (state.clientOnGround && !collision.supported && !onGround) {
            flag(player, FlagType.ON_GROUND_SPOOF, 0.35, "client claimed ground without collision support")
        }
        if (collision.insideSolid) {
            flag(player, FlagType.PHASE, 0.35, "player bounding area intersects a solid block")
        }
        if (collision.loaded && (collision.inLiquid || collision.belowLiquid) && onGround && horizontalDistance > 0.15) {
            flag(player, FlagType.JESUS, 0.3, "moved across liquid while grounded")
        }

        val expectedVertical = if (previousOnGround) 0.0 else -0.08
        val predictionError = abs(verticalDistance - expectedVertical * elapsedTicks)
        if (state.airTicks > 2 && predictionError > 1.0) {
            flag(player, FlagType.PREDICTION, 0.25, "vertical prediction error was $predictionError")
        }
    }

    private fun isExempt(
        player: Player,
        state: PlayerState,
    ): Boolean =
        PlayerStateReg.currentTick <= state.movementExemptUntilTick ||
            PlayerStateReg.currentTick <= state.velocityExemptUntilTick ||
            player.vehicle != null ||
            player.isFlying ||
            player.isAllowFlying ||
            player.gameMode == GameMode.SPECTATOR

    private fun certainty(
        value: Double,
        limit: Double,
    ): Double = ((value / limit - 1.0) / 2.0).coerceIn(0.25, 1.0)

    private const val MAX_CLIENT_MOVE_AGE_NANOS = 100_000_000L
    private const val TICK_NANOS = 50_000_000L
}
