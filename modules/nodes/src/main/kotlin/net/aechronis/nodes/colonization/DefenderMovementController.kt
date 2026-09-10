package net.aechronis.nodes.colonization

import net.aechronis.combat.objects.Vehicle
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.war.Attack
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import kotlin.math.ceil
import kotlin.math.hypot

private const val NAVIGATION_SEGMENT_DISTANCE = 64.0
private const val NAVIGATION_ROUTE_SAMPLE_DISTANCE = 8.0
private const val MAX_NAVIGATION_ROUTE_CHUNKS = 96
private const val MAX_PATH_STARTS_PER_TICK = 1
private const val MAX_PATH_PREPARATIONS_PER_TICK = 2

internal enum class DefenderMovementOutcome {
    READY_FOR_WEAPON_UPDATE,
    RECOVERING_TERRAIN,
}

/** Coordinates defender movement and shares pathfinding budgets across all sessions. */
internal class DefenderMovementController(
    private val sessions: DefenseSessions,
) {
    private var pathStartsRemaining: Int = 0
    private var pathPreparationsRemaining: Int = 0

    fun beginTick() {
        pathStartsRemaining = MAX_PATH_STARTS_PER_TICK
        pathPreparationsRemaining = MAX_PATH_PREPARATIONS_PER_TICK
    }

    fun reset() {
        pathStartsRemaining = 0
        pathPreparationsRemaining = 0
    }

    fun moveDefender(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack?,
        decision: DefenderDecision,
        targetPlayer: Player?,
        now: Long,
    ): DefenderMovementOutcome {
        val directCombatTarget = when (decision.movement) {
            DefenderMovement.RETREAT_FROM_TARGET,
            DefenderMovement.STRAFE_AROUND_TARGET,
            -> targetPlayer

            else -> null
        }
        val navigationGoal = navigationGoalFor(session, defender, attack, decision, targetPlayer)
        if (directCombatTarget != null) {
            moveDefenderInCombat(session, defender, attack, decision, directCombatTarget)
        } else if (navigationGoal != null) {
            return navigateDefender(session, defender, navigationGoal, now)
        } else if (defender.navigation.hasGoal) {
            DefenderTerrainRecovery.stopTerrainRecovery(session, defender)
            defender.navigation.cancel()
            defender.navigation.retainCurrentChunk()
            sessions.reconcileChunks(session)
        }
        return DefenderMovementOutcome.READY_FOR_WEAPON_UPDATE
    }

    private fun moveDefenderInCombat(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack?,
        decision: DefenderDecision,
        target: Player,
    ) {
        if (defender.navigation.hasGoal) {
            defender.navigation.cancel()
            defender.navigation.retainCurrentChunk()
            sessions.reconcileChunks(session)
        }
        val lookAt = Vehicle.protectedVehicleAimPosition(target)
            ?: target.position.add(0.0, target.eyeHeight, 0.0)
        val movementTarget = safeDirectCombatMovementTarget(
            session = session,
            defender = defender,
            attack = attack,
            targetPosition = target.position,
            decision = decision,
        )
        if (movementTarget != null) {
            defender.motor.moveTowards(movementTarget, 0.28, lookAt)
        } else {
            defender.motor.lookTowards(lookAt)
        }
    }

    private fun navigateDefender(
        session: DefenseSession,
        defender: AiDefender,
        goal: NavigationGoal,
        now: Long,
    ): DefenderMovementOutcome {
        if (goal.attack == null) DefenderTerrainRecovery.stopTerrainRecovery(session, defender)
        defender.navigation.discardStaleGoal(goal)
        if (
            goal.attack != null &&
            (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN || defender.navigation.recoveryRequested) &&
            DefenderTerrainRecovery.recoverFlagRouteTerrain(session, defender, goal, now)
        ) {
            return DefenderMovementOutcome.RECOVERING_TERRAIN
        }
        if (pathPreparationsRemaining > 0 && defender.navigation.shouldRequestPath(goal, now)) {
            prepareNavigationPath(session, defender, goal, now)
            pathPreparationsRemaining -= 1
        }
        return DefenderMovementOutcome.READY_FOR_WEAPON_UPDATE
    }

    fun applyPreparedNavigationPaths(
        session: DefenseSession,
        now: Long,
    ) {
        activeDefenders(session).forEach { defender ->
            if (pathStartsRemaining <= 0) return@forEach
            if (!defender.navigation.hasPreparedPath) return@forEach

            val currentGoal = defender.lastDecision?.let { decision ->
                val attack = closestActiveAttack(session, defender.entity.position)
                val player = decision.targetId?.let { targetId ->
                    session.instance.players.firstOrNull { it.uuid == targetId }
                }
                navigationGoalFor(session, defender, attack, decision, player)
            } ?: defender.navigation.currentGoal
            val prepared = defender.navigation.takeCurrentPreparedPath(
                currentGoal = currentGoal,
                eligible = defender.ready && !defender.entity.isRemoved,
            ) ?: return@forEach

            if (!defender.navigation.startPreparedPath(prepared, now)) {
                failNavigationPath(session, defender, prepared.goal, "pathfinder rejected the route")
            }
            pathStartsRemaining -= 1
        }
    }

    private fun navigationGoalFor(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack?,
        decision: DefenderDecision,
        targetPlayer: Player?,
    ): NavigationGoal? {
        if (targetPlayer == null) {
            return when (decision.movement) {
                DefenderMovement.INVESTIGATE_LAST_SEEN -> decision.movementTarget?.let { position ->
                    NavigationGoal(decision.targetId, null, position.toPos())
                }

                DefenderMovement.RETURN_TO_OBJECTIVE -> objectiveNavigationGoal(session, defender, attack, decision)
                else -> attack?.let { objectiveNavigationGoal(session, defender, it) }
            }
        }
        return when (decision.movement) {
            DefenderMovement.APPROACH_TARGET,
            DefenderMovement.INVESTIGATE_LAST_SEEN,
            -> NavigationGoal(targetPlayer.uuid, null, decision.movementTarget?.toPos() ?: targetPlayer.position)

            DefenderMovement.RETREAT_FROM_TARGET,
            DefenderMovement.STRAFE_AROUND_TARGET,
            -> null

            DefenderMovement.RETURN_TO_OBJECTIVE -> objectiveNavigationGoal(session, defender, attack, decision)
            DefenderMovement.HOLD -> null
        }
    }

    private fun flagApproachPositions(
        instance: Instance,
        attack: Attack,
        defender: EntityCreature,
    ): List<Pos> {
        val flagPosition = attack.flagBlock.add(0.5, 0.5, 0.5).asPos()
        val candidates = flagApproachCandidates(instance, attack, defender.eyeHeight, FLAG_REACH.toDouble())
        val safe = candidates
            .filter { candidate ->
                instance.isChunkLoaded(candidate.chunkX(), candidate.chunkZ()) &&
                    isSafeDefenderStandingPosition(instance, candidate) &&
                    hasClearFlagReach(candidate.add(0.0, defender.eyeHeight, 0.0), flagPosition, instance)
            }
            .filterNot { candidate -> candidate.sameBlock(defender.position) }
            .sortedBy(defender.position::distanceSquared)
        if (safe.isNotEmpty()) return safe

        return candidates
            .filterNot { candidate -> candidate.sameBlock(defender.position) }
            .sortedBy(defender.position::distanceSquared)
    }

    private fun prepareNavigationPath(
        session: DefenseSession,
        defender: AiDefender,
        goal: NavigationGoal,
        now: Long,
    ) {
        val (routeTarget, finalSegment) = navigationSegment(defender.entity.position, goal.position)
        val routeChunks = navigationRouteChunks(defender.entity.position, routeTarget)
        val generation = defender.navigation.beginPathPreparation(goal, routeChunks, now)

        sessions.loadChunks(session, routeChunks).whenComplete { _, loadError ->
            ModuleScheduler.scheduleNextTick {
                if (!sessions.isActive(session)) return@scheduleNextTick
                if (activeDefenders(session).none { it === defender } || !defender.navigation.isCurrentGeneration(generation)) {
                    return@scheduleNextTick
                }
                if (loadError != null) {
                    failNavigationPath(session, defender, goal, "could not load route chunks: ${loadError.message}")
                    return@scheduleNextTick
                }

                val endpoint = if (finalSegment) {
                    goal.position
                } else {
                    resolveNavigationWaypoint(session.instance, routeTarget)
                }
                if (endpoint == null) {
                    failNavigationPath(session, defender, goal, "could not find a safe intermediate waypoint")
                    return@scheduleNextTick
                }
                defender.navigation.completePathPreparation(
                    generation = generation,
                    goal = goal,
                    endpoint = endpoint,
                    minimumDistance = navigationMinimumDistance(goal, finalSegment),
                )
            }
        }
        sessions.reconcileChunks(session)
    }

    private fun combatMovementPosition(
        defenderPosition: Pos,
        targetPosition: Pos,
        decision: DefenderDecision,
    ): Pos {
        val deltaX = defenderPosition.x - targetPosition.x
        val deltaZ = defenderPosition.z - targetPosition.z
        val length = hypot(deltaX, deltaZ).coerceAtLeast(0.001)
        val awayX = deltaX / length
        val awayZ = deltaZ / length
        return when (decision.movement) {
            DefenderMovement.RETREAT_FROM_TARGET -> defenderPosition.add(awayX * 6.0, 0.0, awayZ * 6.0)
            DefenderMovement.STRAFE_AROUND_TARGET -> {
                val side = decision.strafeDirection.toDouble()
                defenderPosition.add(-awayZ * side * 4.0, 0.0, awayX * side * 4.0)
            }
            else -> decision.movementTarget?.toPos() ?: targetPosition
        }
    }

    private fun safeDirectCombatMovementTarget(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack?,
        targetPosition: Pos,
        decision: DefenderDecision,
    ): Pos? {
        if (!defender.entity.isOnGround) return null
        val objective = campaignObjective(defender, attack)
        val preferred = combatMovementPosition(defender.entity.position, targetPosition, decision)
        val alternate = if (decision.movement == DefenderMovement.STRAFE_AROUND_TARGET) {
            combatMovementPosition(
                defender.entity.position,
                targetPosition,
                decision.copy(strafeDirection = -decision.strafeDirection),
            )
        } else {
            null
        }
        return chooseSafeDirectMovementTarget(
            position = defender.entity.position,
            preferredTarget = preferred,
            alternateTarget = alternate,
            speed = 0.28,
        ) { nextPosition ->
            session.instance.worldBorder.inBounds(nextPosition) &&
                session.instance.isChunkLoaded(nextPosition.chunkX(), nextPosition.chunkZ()) &&
                Territory.fromBlock(nextPosition.blockX(), nextPosition.blockZ())?.town === session.targetTown &&
                defender.brain.isWithinObjectiveLeash(
                    objective.toDefenderPoint(),
                    nextPosition.toDefenderPoint(),
                ) &&
                isSafeDefenderStandingPosition(session.instance, nextPosition)
        }
    }

    private fun objectiveNavigationGoal(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
    ): NavigationGoal? {
        val approaches = flagApproachPositions(session.instance, attack, defender.entity)
        if (approaches.isEmpty()) return null
        return NavigationGoal(null, attack, defender.navigation.approachFor(approaches))
    }

    private fun objectiveNavigationGoal(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack?,
        decision: DefenderDecision,
    ): NavigationGoal? = if (attack != null) {
        objectiveNavigationGoal(session, defender, attack)
    } else {
        NavigationGoal(
            playerId = null,
            attack = null,
            position = decision.movementTarget?.toPos() ?: defender.guardPosition,
        )
    }

    private fun failNavigationPath(
        session: DefenseSession,
        defender: AiDefender,
        goal: NavigationGoal,
        reason: String,
    ) {
        val firstFailure = defender.navigation.failPath(goal)
        sessions.reconcileChunks(session)
        if (firstFailure) {
            val destination = when {
                goal.playerId != null -> "player ${goal.playerId}"
                goal.attack != null -> "the colonization flag"
                else -> "the town guard position"
            }
            System.err.println("[Nodes] AI defender could not walk toward $destination: $reason")
        }
    }

    private fun navigationSegment(
        start: Pos,
        goal: Pos,
    ): Pair<Pos, Boolean> {
        val distance = start.distance(goal)
        if (distance <= NAVIGATION_SEGMENT_DISTANCE) return goal to true
        val progress = NAVIGATION_SEGMENT_DISTANCE / distance
        return Pos(
            start.x + (goal.x - start.x) * progress,
            start.y + (goal.y - start.y) * progress,
            start.z + (goal.z - start.z) * progress,
        ) to false
    }

    private fun navigationRouteChunks(
        start: Pos,
        target: Pos,
    ): Set<Coord> {
        val centerLineChunks = linkedSetOf<Coord>()
        val distance = horizontalDistance(start, target)
        val steps = maxOf(1, ceil(distance / NAVIGATION_ROUTE_SAMPLE_DISTANCE).toInt())
        for (step in 0..steps) {
            val progress = step.toDouble() / steps
            val point = Pos(
                start.x + (target.x - start.x) * progress,
                start.y + (target.y - start.y) * progress,
                start.z + (target.z - start.z) * progress,
            )
            centerLineChunks.add(Coord(point.chunkX(), point.chunkZ()))
        }

        val corridor = linkedSetOf<Coord>()
        corridor.addAll(centerLineChunks)
        for (center in centerLineChunks) {
            for (offsetX in -1..1) {
                for (offsetZ in -1..1) {
                    if (corridor.size >= MAX_NAVIGATION_ROUTE_CHUNKS) return corridor
                    corridor.add(Coord(center.x + offsetX, center.z + offsetZ))
                }
            }
        }
        return corridor
    }

    private fun resolveNavigationWaypoint(instance: Instance, approximate: Pos): Pos? {
        val offsets = intArrayOf(0, 4, -4, 8, -8)
        for (offsetX in offsets) {
            for (offsetZ in offsets) {
                val candidate = approximate.add(offsetX.toDouble(), 0.0, offsetZ.toDouble())
                if (!instance.worldBorder.inBounds(candidate) || !instance.isChunkLoaded(candidate)) continue
                resolveSafeSurfaceNear(instance, candidate, approximate.blockY())?.let { return it }
            }
        }
        return null
    }

    private fun horizontalDistance(first: Pos, second: Pos): Double = hypot(first.x - second.x, first.z - second.z)
}
