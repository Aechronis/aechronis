package net.aechronis.watchdog.checks

import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.FlagType
import net.aechronis.watchdog.objects.PlayerState
import net.aechronis.watchdog.objects.PlayerStateReg
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import kotlin.math.abs
import kotlin.math.ceil
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
            if (enabled(config, FlagType.BAD_PACKETS)) {
                flag(player, FlagType.BAD_PACKETS, 1.0, "non-finite movement position")
            }
            return enabled(config, FlagType.BAD_PACKETS)
        }

        val nowNanos = System.nanoTime()
        val collision = CollisionOracle.sample(player, position)
        if (isExempt(player, state)) {
            accept(state, position, onGround, nowNanos, 0, collision)
            return false
        }

        val previous = state.lastAcceptedPosition
        val previousAtNanos = state.lastAcceptedAtNanos
        if (previous == null || previousAtNanos == 0L) {
            accept(state, position, onGround, nowNanos, nextAirTicks(onGround, collision), collision)
            return false
        }

        val previousCollision = state.lastCollision ?: CollisionOracle.sample(player, previous)
        val elapsedTicks = elapsedTicks(nowNanos - previousAtNanos)
        val horizontalDistance = hypot(position.x - previous.x, position.z - previous.z)
        val horizontalLimit = config.maxHorizontalMovePerTick * elapsedTicks * MOVEMENT_TOLERANCE
        val verticalDistance = position.y - previous.y
        val environment = collision.movementExempt
        val previousEnvironment = previousCollision.movementExempt
        val airTicks = nextAirTicks(onGround, collision, state.airTicks)
        var reject = false

        if (horizontalDistance > horizontalLimit && enabled(config, FlagType.SPEED)) {
            val certainty = certainty(horizontalDistance, horizontalLimit)
            flag(
                player,
                FlagType.SPEED,
                certainty,
                "moved $horizontalDistance blocks horizontally over $elapsedTicks ticks",
            )
            reject = reject || certainty >= HARD_CERTAINTY && horizontalDistance > HARD_HORIZONTAL_DISTANCE
        }

        if (!onGround &&
            !environment &&
            !previousEnvironment &&
            verticalDistance > config.maxUpwardMovePerTick * elapsedTicks * MOVEMENT_TOLERANCE &&
            enabled(config, FlagType.FLIGHT)
        ) {
            val limit = config.maxUpwardMovePerTick * elapsedTicks * MOVEMENT_TOLERANCE
            val certainty = certainty(verticalDistance, limit)
            flag(
                player,
                FlagType.FLIGHT,
                certainty,
                "moved $verticalDistance blocks upward while unsupported",
            )
            state.flightBuffer = (state.flightBuffer + certainty).coerceAtMost(MAX_FLIGHT_BUFFER)
            reject = reject || certainty >= HARD_CERTAINTY && verticalDistance > HARD_VERTICAL_DISTANCE
        } else if (!environment && !previousEnvironment && !onGround && abs(verticalDistance) < HOVER_DELTA) {
            state.flightBuffer = (state.flightBuffer - FLIGHT_DECAY).coerceAtLeast(0.0)
        } else {
            state.flightBuffer = (state.flightBuffer - FLIGHT_DECAY * 2.0).coerceAtLeast(0.0)
        }

        if (!onGround &&
            !environment &&
            !previousEnvironment &&
            airTicks >= HOVER_TICKS &&
            abs(verticalDistance) < HOVER_DELTA &&
            enabled(config, FlagType.FLIGHT)
        ) {
            val certainty = (HOVER_CERTAINTY + state.flightBuffer / MAX_FLIGHT_BUFFER * 0.15).coerceAtMost(0.6)
            flag(player, FlagType.FLIGHT, certainty, "hovered for $airTicks unsupported air ticks")
        }

        if (collision.loaded &&
            state.clientOnGround &&
            !collision.supported &&
            !collision.movementExempt &&
            !onGround &&
            enabled(config, FlagType.ON_GROUND_SPOOF)
        ) {
            flag(player, FlagType.ON_GROUND_SPOOF, 0.35, "client claimed ground without collision support")
        }

        val enteredSolid =
            (!previousCollision.insideSolid && collision.insideSolid) ||
                CollisionOracle.crossesSolid(player, previous, position)
        if (collision.loaded && enabled(config, FlagType.PHASE) && enteredSolid) {
            flag(player, FlagType.PHASE, 0.35, "entered a solid collision shape")
            reject = true
        }

        if (collision.loaded &&
            collision.belowLiquid &&
            !collision.inLiquid &&
            onGround &&
            horizontalDistance > LIQUID_HORIZONTAL_DISTANCE &&
            enabled(config, FlagType.JESUS)
        ) {
            flag(player, FlagType.JESUS, 0.3, "moved across liquid while grounded")
        }

        if (airTicks > 2 && !environment && !previousEnvironment) {
            val expectedVertical = if (previousCollision.supported) 0.0 else -GRAVITY
            val predictionError = abs(verticalDistance - expectedVertical * elapsedTicks)
            if (predictionError > PREDICTION_ERROR && enabled(config, FlagType.PREDICTION)) {
                flag(player, FlagType.PREDICTION, 0.25, "vertical prediction error was $predictionError")
            }
        }

        if (reject) return true

        accept(state, position, onGround, nowNanos, airTicks, collision)
        return false
    }

    private fun accept(
        state: PlayerState,
        position: Pos,
        onGround: Boolean,
        nowNanos: Long,
        airTicks: Int,
        collision: CollisionSnapshot,
    ) {
        state.lastPosition = position
        state.lastAcceptedPosition = position
        state.lastAcceptedOnGround = onGround
        state.lastAcceptedAtNanos = nowNanos
        state.airTicks = airTicks
        state.lastCollision = collision
    }

    private fun nextAirTicks(
        onGround: Boolean,
        collision: CollisionSnapshot,
        current: Int = 0,
    ): Int = if (onGround || collision.movementExempt) 0 else current + 1

    private fun elapsedTicks(nanos: Long): Long =
        ceil(nanos.coerceAtLeast(1L).toDouble() / TICK_NANOS).toLong().coerceIn(1L, MAX_ELAPSED_TICKS)

    private fun isExempt(
        player: Player,
        state: PlayerState,
    ): Boolean =
        PlayerStateReg.currentTick <= state.movementExemptUntilTick ||
            PlayerStateReg.currentTick <= state.velocityExemptUntilTick ||
            player.vehicle != null ||
            player.isFlying ||
            player.isAllowFlying ||
            player.gameMode == GameMode.CREATIVE ||
            player.gameMode == GameMode.SPECTATOR

    private fun enabled(
        config: WatchdogConfig,
        type: FlagType,
    ): Boolean = type in config.enabledChecks

    private fun certainty(
        value: Double,
        limit: Double,
    ): Double = ((value / limit - 1.0) / 2.0).coerceIn(0.25, 1.0)

    private const val MOVEMENT_TOLERANCE = 1.25
    private const val MAX_ELAPSED_TICKS = 20L
    private const val TICK_NANOS = 50_000_000L
    private const val HARD_CERTAINTY = 0.75
    private const val HARD_HORIZONTAL_DISTANCE = 2.0
    private const val HARD_VERTICAL_DISTANCE = 1.2
    private const val HOVER_TICKS = 15
    private const val HOVER_DELTA = 0.01
    private const val HOVER_CERTAINTY = 0.3
    private const val MAX_FLIGHT_BUFFER = 2.0
    private const val FLIGHT_DECAY = 0.04
    private const val GRAVITY = 0.08
    private const val PREDICTION_ERROR = 1.0
    private const val LIQUID_HORIZONTAL_DISTANCE = 0.15
}
