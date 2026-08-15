package net.aechronis.nodes.colonization

import java.util.UUID
import kotlin.math.sqrt
import kotlin.random.Random

internal enum class DefenderMovement {
    HOLD,
    APPROACH_TARGET,
    RETREAT_FROM_TARGET,
    STRAFE_AROUND_TARGET,
    INVESTIGATE_LAST_SEEN,
    RETURN_TO_OBJECTIVE,
}

internal data class DefenderPoint(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Defender points must be finite" }
    }

    fun distanceSquared(other: DefenderPoint): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    fun distance(other: DefenderPoint): Double = sqrt(distanceSquared(other))
}

internal data class DefenderTargetObservation(
    val id: UUID,
    val position: DefenderPoint,
    val visible: Boolean,
)

internal data class DefenderObservation(
    val nowMillis: Long,
    val position: DefenderPoint,
    val objectivePosition: DefenderPoint,
    val targets: List<DefenderTargetObservation>,
    val weaponRange: Double,
    // true only when the runtime has verified reach and line of sight to the flag
    val canMineObjective: Boolean = false,
) {
    init {
        require(nowMillis >= 0) { "Time cannot be negative" }
        require(weaponRange.isFinite() && weaponRange > 0.0) { "Weapon range must be positive and finite" }
    }
}

private data class DefenderRangeBand(
    val minimum: Double,
    val maximum: Double,
)

internal data class DefenderMemory(
    val targetId: UUID? = null,
    val lastSeenPosition: DefenderPoint? = null,
    val lastSeenAtMillis: Long? = null,
    val targetWasVisible: Boolean = false,
    val reactionReadyAtMillis: Long = 0,
    val strafeDirection: Int = 1,
    val strafeUntilMillis: Long = 0,
)

internal data class DefenderDecision(
    val targetId: UUID?,
    val aimPosition: DefenderPoint?,
    val movement: DefenderMovement,
    val movementTarget: DefenderPoint?,
    val wantsToFire: Boolean,
    val mineObjective: Boolean,
    // -1 is left     +1 is right
    val strafeDirection: Int,
    val memory: DefenderMemory,
)

internal data class DefenderBrainConfig(
    val awarenessRadius: Double = 56.0,
    val objectiveLeashRadius: Double = 64.0,
    val guardRadius: Double = 6.0,
    val desiredRangeMinimumFraction: Double = 0.25,
    val desiredRangeMaximumFraction: Double = 0.65,
    val targetStickinessBonus: Double = 8.0,
    val targetSwitchHysteresis: Double = 3.0,
    val objectiveProximityWeight: Double = 0.35,
    val lastSeenMemoryMillis: Long = 2_500,
    val lastSeenArrivalRadius: Double = 1.5,
    val reacquireReactionAfterMillis: Long = 600,
    val reactionDelayMillis: LongRange = 160L..320L,
    val strafeIntervalMillis: LongRange = 650L..1_300L,
) {
    init {
        require(awarenessRadius > 0.0 && awarenessRadius.isFinite())
        require(objectiveLeashRadius > 0.0 && objectiveLeashRadius.isFinite())
        require(guardRadius in 0.0..objectiveLeashRadius)
        require(desiredRangeMinimumFraction in 0.0..<desiredRangeMaximumFraction)
        require(desiredRangeMaximumFraction <= 1.0)
        require(targetStickinessBonus >= 0.0 && targetStickinessBonus.isFinite())
        require(targetSwitchHysteresis >= 0.0 && targetSwitchHysteresis.isFinite())
        require(objectiveProximityWeight >= 0.0 && objectiveProximityWeight.isFinite())
        require(lastSeenMemoryMillis >= 0)
        require(lastSeenArrivalRadius >= 0.0 && lastSeenArrivalRadius.isFinite())
        require(reacquireReactionAfterMillis >= 0)
        require(!reactionDelayMillis.isEmpty() && reactionDelayMillis.first >= 0 && reactionDelayMillis.last < Long.MAX_VALUE)
        require(!strafeIntervalMillis.isEmpty() && strafeIntervalMillis.first > 0 && strafeIntervalMillis.last < Long.MAX_VALUE)
    }
}

internal class DefenderBrain(
    private val config: DefenderBrainConfig = DefenderBrainConfig(),
    private val random: Random = Random.Default,
) {
    fun isPotentialTarget(
        position: DefenderPoint,
        objectivePosition: DefenderPoint,
        targetPosition: DefenderPoint,
    ): Boolean = position.distance(targetPosition) <= config.awarenessRadius &&
        isWithinObjectiveLeash(objectivePosition, targetPosition)

    fun isWithinObjectiveLeash(
        objectivePosition: DefenderPoint,
        position: DefenderPoint,
    ): Boolean = objectivePosition.distance(position) <= config.objectiveLeashRadius

    fun decide(
        observation: DefenderObservation,
        previous: DefenderMemory = DefenderMemory(),
    ): DefenderDecision {
        val rangeBand = DefenderRangeBand(
            minimum = observation.weaponRange * config.desiredRangeMinimumFraction,
            maximum = observation.weaponRange * config.desiredRangeMaximumFraction,
        )
        val visibleTarget = selectVisibleTarget(observation, previous)
        var memory = rememberTarget(observation, previous, visibleTarget)
        val hasRememberedTarget = memory.targetId != null && memory.lastSeenPosition != null
        val targetPosition = memory.lastSeenPosition
        val targetVisible = visibleTarget?.id == memory.targetId
        val distanceToObjective = observation.position.distance(observation.objectivePosition)
        val outsideLeash = distanceToObjective > config.objectiveLeashRadius
        val targetDistance = targetPosition?.let(observation.position::distance)
        memory = updateStrafe(observation, memory, targetVisible, targetDistance, rangeBand)

        val movement = movementFor(
            outsideLeash = outsideLeash,
            hasRememberedTarget = hasRememberedTarget,
            targetVisible = targetVisible,
            targetDistance = targetDistance,
            distanceToObjective = distanceToObjective,
            rangeBand = rangeBand,
        )
        val movementTarget = when (movement) {
            DefenderMovement.RETURN_TO_OBJECTIVE -> observation.objectivePosition
            DefenderMovement.APPROACH_TARGET,
            DefenderMovement.RETREAT_FROM_TARGET,
            DefenderMovement.STRAFE_AROUND_TARGET,
            DefenderMovement.INVESTIGATE_LAST_SEEN,
            -> targetPosition

            DefenderMovement.HOLD -> null
        }

        val canFire =
            targetVisible &&
                targetDistance != null &&
                targetDistance <= observation.weaponRange &&
                !outsideLeash &&
                observation.nowMillis >= memory.reactionReadyAtMillis
        val mineObjective = !hasRememberedTarget && observation.canMineObjective

        return DefenderDecision(
            targetId = memory.targetId,
            aimPosition = targetPosition ?: observation.objectivePosition.takeIf { mineObjective },
            movement = movement,
            movementTarget = movementTarget,
            wantsToFire = canFire,
            mineObjective = mineObjective,
            strafeDirection = memory.strafeDirection.takeIf { movement == DefenderMovement.STRAFE_AROUND_TARGET } ?: 0,
            memory = memory,
        )
    }

    private fun movementFor(
        outsideLeash: Boolean,
        hasRememberedTarget: Boolean,
        targetVisible: Boolean,
        targetDistance: Double?,
        distanceToObjective: Double,
        rangeBand: DefenderRangeBand,
    ): DefenderMovement = when {
        outsideLeash -> DefenderMovement.RETURN_TO_OBJECTIVE
        hasRememberedTarget && !targetVisible && targetDistance != null && targetDistance > config.lastSeenArrivalRadius -> {
            DefenderMovement.INVESTIGATE_LAST_SEEN
        }

        hasRememberedTarget && !targetVisible -> DefenderMovement.HOLD
        targetDistance != null && targetDistance < rangeBand.minimum -> DefenderMovement.RETREAT_FROM_TARGET
        targetDistance != null && targetDistance > rangeBand.maximum -> DefenderMovement.APPROACH_TARGET
        targetDistance != null -> DefenderMovement.STRAFE_AROUND_TARGET
        !hasRememberedTarget && distanceToObjective > config.guardRadius -> DefenderMovement.RETURN_TO_OBJECTIVE
        else -> DefenderMovement.HOLD
    }

    private fun selectVisibleTarget(
        observation: DefenderObservation,
        previous: DefenderMemory,
    ): DefenderTargetObservation? {
        val eligible = observation.targets.filter { target ->
            target.visible &&
                isPotentialTarget(observation.position, observation.objectivePosition, target.position)
        }
        if (eligible.isEmpty()) return null

        val current = eligible.firstOrNull { it.id == previous.targetId }
            ?: return eligible.maxBy { target -> targetScore(observation, target) }
        val alternative = eligible
            .asSequence()
            .filterNot { it.id == current.id }
            .maxByOrNull { target -> targetScore(observation, target) }
            ?: return current
        val currentScore = targetScore(observation, current) + config.targetStickinessBonus
        val alternativeScore = targetScore(observation, alternative)
        return if (alternativeScore > currentScore + config.targetSwitchHysteresis) alternative else current
    }

    private fun targetScore(
        observation: DefenderObservation,
        target: DefenderTargetObservation,
    ): Double = -observation.position.distance(target.position) -
        config.objectiveProximityWeight * observation.objectivePosition.distance(target.position)

    private fun rememberTarget(
        observation: DefenderObservation,
        previous: DefenderMemory,
        visibleTarget: DefenderTargetObservation?,
    ): DefenderMemory {
        if (visibleTarget != null) {
            val sameTarget = previous.targetId == visibleTarget.id
            val unseenFor = previous.lastSeenAtMillis?.let { elapsedMillis(observation.nowMillis, it) }
            val needsReaction =
                !sameTarget ||
                    (!previous.targetWasVisible && (unseenFor == null || unseenFor > config.reacquireReactionAfterMillis))
            if (needsReaction) {
                val reactionReadyAt = safeAdd(observation.nowMillis, randomLong(config.reactionDelayMillis))
                return DefenderMemory(
                    targetId = visibleTarget.id,
                    lastSeenPosition = visibleTarget.position,
                    lastSeenAtMillis = observation.nowMillis,
                    targetWasVisible = true,
                    reactionReadyAtMillis = reactionReadyAt,
                    strafeDirection = if (random.nextBoolean()) -1 else 1,
                    strafeUntilMillis = safeAdd(observation.nowMillis, randomLong(config.strafeIntervalMillis)),
                )
            }
            return previous.copy(
                targetId = visibleTarget.id,
                lastSeenPosition = visibleTarget.position,
                lastSeenAtMillis = observation.nowMillis,
                targetWasVisible = true,
            )
        }

        val lastSeenAt = previous.lastSeenAtMillis
        val lastSeenPosition = previous.lastSeenPosition
        val memoryIsUsable =
            previous.targetId != null &&
                lastSeenAt != null &&
                lastSeenPosition != null &&
                elapsedMillis(observation.nowMillis, lastSeenAt) <= config.lastSeenMemoryMillis &&
                observation.objectivePosition.distance(lastSeenPosition) <= config.objectiveLeashRadius
        return if (memoryIsUsable) {
            previous.copy(targetWasVisible = false)
        } else {
            DefenderMemory()
        }
    }

    private fun updateStrafe(
        observation: DefenderObservation,
        previous: DefenderMemory,
        targetVisible: Boolean,
        targetDistance: Double?,
        rangeBand: DefenderRangeBand,
    ): DefenderMemory {
        if (!targetVisible || targetDistance == null || targetDistance !in rangeBand.minimum..rangeBand.maximum) {
            return previous
        }
        if (observation.nowMillis < previous.strafeUntilMillis) return previous
        return previous.copy(
            strafeDirection = -previous.strafeDirection,
            strafeUntilMillis = safeAdd(observation.nowMillis, randomLong(config.strafeIntervalMillis)),
        )
    }

    private fun randomLong(range: LongRange): Long = if (range.first == range.last) {
        range.first
    } else {
        random.nextLong(range.first, range.last + 1)
    }

    private fun elapsedMillis(
        nowMillis: Long,
        thenMillis: Long,
    ): Long = if (nowMillis >= thenMillis) nowMillis - thenMillis else 0

    private fun safeAdd(
        left: Long,
        right: Long,
    ): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
