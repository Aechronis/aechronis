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
    ): Boolean {
        if (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()) {
            flag(player, FlagType.BAD_PACKETS, 1.0, "non-finite movement position")
            return true
        }

        val previous = state.lastAcceptedPosition ?: player.position
        val previousAtNanos = state.lastAcceptedAtNanos
        val previousOnGround = state.lastAcceptedOnGround
        val nowNanos = System.nanoTime()
        val elapsedTicks =
            if (previousAtNanos == 0L) {
                1L
            } else {
                ((nowNanos - previousAtNanos) / TICK_NANOS).coerceIn(1L, MAX_ELAPSED_TICKS)
            }
        val horizontalDistance = hypot(position.x - previous.x, position.z - previous.z)
        val horizontalLimit = config.maxHorizontalMovePerTick * elapsedTicks * MOVEMENT_TOLERANCE
        val verticalDistance = position.y - previous.y
        val nextAirTicks = if (onGround) 0 else state.airTicks + 1

        if (isExempt(player, state)) {
            accept(state, position, onGround, nowNanos, nextAirTicks)
            return false
        }

        val collision = CollisionOracle.sample(player, position)
        var reject = false
        if (horizontalDistance > horizontalLimit) {
            flag(
                player,
                FlagType.SPEED,
                certainty(horizontalDistance, horizontalLimit),
                "moved $horizontalDistance blocks horizontally over $elapsedTicks ticks",
            )
            reject = true
        }

        if (verticalDistance > config.maxUpwardMovePerTick * elapsedTicks && !onGround) {
            flag(
                player,
                FlagType.FLIGHT,
                certainty(verticalDistance, config.maxUpwardMovePerTick * elapsedTicks),
                "moved $verticalDistance blocks upward while airborne",
            )
            reject = true
        }
        if (!onGround && nextAirTicks >= 15 && abs(verticalDistance) < 0.01) {
            flag(player, FlagType.FLIGHT, 0.35, "hovered for $nextAirTicks air ticks")
            reject = true
        }

        if (collision.loaded && state.clientOnGround && !collision.supported && !onGround) {
            flag(player, FlagType.ON_GROUND_SPOOF, 0.35, "client claimed ground without collision support")
        }
        if (collision.loaded && collision.insideSolid) {
            flag(player, FlagType.PHASE, 0.35, "player bounding area intersects a solid block")
            reject = true
        }
        if (collision.loaded && (collision.inLiquid || collision.belowLiquid) && onGround && horizontalDistance > 0.15) {
            flag(player, FlagType.JESUS, 0.3, "moved across liquid while grounded")
        }

        val expectedVertical = if (previousOnGround) 0.0 else -0.08
        val predictionError = abs(verticalDistance - expectedVertical * elapsedTicks)
        if (nextAirTicks > 2 && predictionError > 1.0) {
            flag(player, FlagType.PREDICTION, 0.25, "vertical prediction error was $predictionError")
        }

        if (reject) return true

        accept(state, position, onGround, nowNanos, nextAirTicks)
        return false
    }

    private fun accept(
        state: PlayerState,
        position: Pos,
        onGround: Boolean,
        nowNanos: Long,
        airTicks: Int,
    ) {
        state.lastPosition = position
        state.lastAcceptedPosition = position
        state.lastAcceptedOnGround = onGround
        state.lastAcceptedAtNanos = nowNanos
        state.airTicks = airTicks
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

    private const val MOVEMENT_TOLERANCE = 1.25
    private const val MAX_ELAPSED_TICKS = 20L
    private const val TICK_NANOS = 50_000_000L
}
