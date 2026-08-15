package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.war.Attack
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.pathfinding.PPath
import java.util.UUID

internal data class NavigationGoal(
    val playerId: UUID?,
    val attack: Attack?,
    val position: Pos,
)

internal data class PreparedNavigationPath(
    val generation: Int,
    val goal: NavigationGoal,
    val endpoint: Pos,
    val minimumDistance: Double,
)

internal class DefenderNavigator(
    private val entity: EntityCreature,
) {
    private var terrainRecoveryRequested: Boolean = false
    private var lastPathRefreshAt: Long = 0
    private var pathFailureCount: Int = 0
    private var nextApproachIndex: Int = 0
    private var pathGeneration: Int = 0
    private var pathLoadPending: Boolean = false
    private var pathLoadStartedAt: Long = 0
    private var requestedNavigationGoal: NavigationGoal? = null
    private var activeNavigationGoal: NavigationGoal? = null
    private var preparedNavigationPath: PreparedNavigationPath? = null
    private var navigationChunks: Set<Coord> = emptySet()
    private var lastNavigationPosition: Pos? = null
    private var lastNavigationProgressAt: Long = 0
    private var scaffoldNavigation: Boolean = false
    private var highestScaffoldY: Double = Double.NEGATIVE_INFINITY

    val currentGoal: NavigationGoal? get() = requestedNavigationGoal ?: activeNavigationGoal
    val chunks: Set<Coord> get() = navigationChunks
    val recoveryRequested: Boolean get() = terrainRecoveryRequested
    val hasGoal: Boolean get() = requestedNavigationGoal != null || activeNavigationGoal != null
    val hasPreparedPath: Boolean get() = preparedNavigationPath != null

    fun references(attack: Attack): Boolean = (requestedNavigationGoal ?: activeNavigationGoal)?.attack === attack ||
        preparedNavigationPath?.goal?.attack === attack

    fun approachFor(approaches: List<Pos>): Pos = flagApproachForAttempt(approaches, nextApproachIndex)

    fun advanceApproach() {
        nextApproachIndex += 1
    }

    fun requestTerrainRecovery() {
        terrainRecoveryRequested = true
    }

    fun clearTerrainRecovery() {
        terrainRecoveryRequested = false
    }

    fun discardStaleGoal(goal: NavigationGoal) {
        val requested = requestedNavigationGoal ?: return
        if (requested.playerId != goal.playerId || requested.attack !== goal.attack) cancel()
    }

    fun cancel() {
        pathGeneration += 1
        pathLoadPending = false
        pathLoadStartedAt = 0
        preparedNavigationPath = null
        requestedNavigationGoal = null
        activeNavigationGoal = null
        scaffoldNavigation = false
        highestScaffoldY = Double.NEGATIVE_INFINITY
        entity.navigator.reset()
    }

    fun retainCurrentChunk() {
        retainChunkAt(entity.position)
    }

    fun retainChunkAt(position: Pos) {
        navigationChunks = setOf(Coord(position.chunkX(), position.chunkZ()))
    }

    fun clearChunks() {
        navigationChunks = emptySet()
    }

    fun shouldRequestPath(
        goal: NavigationGoal,
        nowMillis: Long,
    ): Boolean {
        if (pathLoadPending) {
            if (nowMillis - pathLoadStartedAt < PATH_RETRY_MILLIS) return false
            pathGeneration += 1
            pathLoadPending = false
            preparedNavigationPath = null
        }
        if (preparedNavigationPath != null) return false
        val navigator = entity.navigator
        val position = entity.position
        val previousPosition = lastNavigationPosition
        val madeProgress = hasNavigationProgress(
            previous = previousPosition,
            current = position,
            scaffoldNavigation = scaffoldNavigation,
            previousHighestY = highestScaffoldY,
        )
        val climbedScaffold = scaffoldNavigation && hasScaffoldClimbProgress(highestScaffoldY, position.y)
        if (madeProgress) {
            lastNavigationPosition = position
            lastNavigationProgressAt = nowMillis
            if (climbedScaffold) highestScaffoldY = position.y
        }

        val activeGoal = activeNavigationGoal ?: return true
        if (activeGoal.playerId != goal.playerId || activeGoal.attack !== goal.attack) return true
        val playerMoved = goal.playerId != null &&
            activeGoal.position.distanceSquared(goal.position) >= PATH_GOAL_MOVEMENT_DISTANCE * PATH_GOAL_MOVEMENT_DISTANCE
        if (playerMoved && nowMillis - lastPathRefreshAt >= PLAYER_PATH_REFRESH_MILLIS) {
            navigator.reset()
            return true
        }
        if (nowMillis - lastPathRefreshAt < PATH_RETRY_MILLIS) return false

        return when (navigator.state) {
            PPath.State.INVALID,
            PPath.State.TERMINATED,
            -> {
                navigator.reset()
                if (scaffoldNavigation) {
                    advanceApproach()
                    scaffoldNavigation = false
                    highestScaffoldY = Double.NEGATIVE_INFINITY
                }
                if (goal.attack != null) {
                    requestTerrainRecovery()
                    false
                } else {
                    true
                }
            }
            PPath.State.FOLLOWING -> {
                val stallMillis = if (scaffoldNavigation) SCAFFOLD_PATH_STALL_MILLIS else PATH_STALL_MILLIS
                val stalled = nowMillis - lastNavigationProgressAt >= stallMillis
                if (stalled) {
                    if (scaffoldNavigation) advanceApproach()
                    navigator.reset()
                    scaffoldNavigation = false
                    highestScaffoldY = Double.NEGATIVE_INFINITY
                    if (goal.attack != null) requestTerrainRecovery()
                }
                stalled && goal.attack == null
            }
            PPath.State.CALCULATING,
            PPath.State.TERMINATING,
            PPath.State.COMPUTED,
            PPath.State.BEST_EFFORT,
            -> false
        }
    }

    fun beginPathPreparation(
        goal: NavigationGoal,
        routeChunks: Set<Coord>,
        nowMillis: Long,
    ): Int {
        val generation = ++pathGeneration
        pathLoadPending = true
        pathLoadStartedAt = nowMillis
        requestedNavigationGoal = goal
        lastPathRefreshAt = nowMillis
        navigationChunks = routeChunks
        return generation
    }

    fun isCurrentGeneration(generation: Int): Boolean = pathGeneration == generation

    fun completePathPreparation(
        generation: Int,
        goal: NavigationGoal,
        endpoint: Pos,
        minimumDistance: Double,
    ): Boolean {
        if (!isCurrentGeneration(generation)) return false
        pathLoadPending = false
        pathLoadStartedAt = 0
        preparedNavigationPath = PreparedNavigationPath(generation, goal, endpoint, minimumDistance)
        return true
    }

    fun takeCurrentPreparedPath(
        currentGoal: NavigationGoal?,
        eligible: Boolean,
    ): PreparedNavigationPath? {
        val prepared = preparedNavigationPath ?: return null
        if (!eligible || prepared.generation != pathGeneration) {
            preparedNavigationPath = null
            return null
        }

        val playerMoved = currentGoal?.playerId != null &&
            currentGoal.position.distanceSquared(prepared.goal.position) >=
            PATH_GOAL_MOVEMENT_DISTANCE * PATH_GOAL_MOVEMENT_DISTANCE
        val stale = currentGoal == null ||
            currentGoal.playerId != prepared.goal.playerId ||
            currentGoal.attack !== prepared.goal.attack ||
            playerMoved
        if (stale) {
            cancel()
            return null
        }

        preparedNavigationPath = null
        activeNavigationGoal = prepared.goal
        return prepared
    }

    fun startPreparedPath(
        prepared: PreparedNavigationPath,
        nowMillis: Long,
    ): Boolean {
        lastPathRefreshAt = nowMillis
        if (!setNavigationPath(prepared.endpoint, prepared.minimumDistance)) return false

        pathFailureCount = 0
        clearTerrainRecovery()
        lastNavigationPosition = entity.position
        lastNavigationProgressAt = nowMillis
        return true
    }

    fun failPath(goal: NavigationGoal): Boolean {
        pathLoadPending = false
        pathLoadStartedAt = 0
        preparedNavigationPath = null
        requestedNavigationGoal = goal
        activeNavigationGoal = goal
        scaffoldNavigation = false
        highestScaffoldY = Double.NEGATIVE_INFINITY
        entity.navigator.reset()
        retainCurrentChunk()
        pathFailureCount += 1
        if (goal.attack != null) {
            advanceApproach()
            requestTerrainRecovery()
        }
        return pathFailureCount == 1
    }

    fun airborneRecoveryDue(nowMillis: Long): Boolean {
        val stallMillis = if (scaffoldNavigation) SCAFFOLD_PATH_STALL_MILLIS else PATH_STALL_MILLIS
        return nowMillis - lastNavigationProgressAt >= stallMillis
    }

    fun restartForTerrainRecovery(nowMillis: Long) {
        lastNavigationProgressAt = nowMillis
        cancel()
        requestTerrainRecovery()
    }

    fun navigateOntoPlacedTerrain(
        goal: NavigationGoal,
        placedBlock: BlockVec,
        nowMillis: Long,
    ) {
        cancel()
        requestedNavigationGoal = goal
        activeNavigationGoal = goal
        lastPathRefreshAt = nowMillis
        scaffoldNavigation = true
        highestScaffoldY = entity.position.y
        val standingPosition = placedBlock.add(0.5, 1.0, 0.5).asPos()
        val generation = pathGeneration
        val reachedScaffold = {
            if (pathGeneration == generation && activeNavigationGoal === goal) {
                scaffoldNavigation = false
                highestScaffoldY = Double.NEGATIVE_INFINITY
                requestTerrainRecovery()
            }
        }
        if (setNavigationPath(standingPosition, PATH_MINIMUM_DISTANCE, reachedScaffold)) {
            lastNavigationPosition = entity.position
            lastNavigationProgressAt = nowMillis
        } else {
            scaffoldNavigation = false
            highestScaffoldY = Double.NEGATIVE_INFINITY
            requestTerrainRecovery()
        }
    }

    private fun setNavigationPath(
        endpoint: Pos,
        minimumDistance: Double,
        onComplete: (() -> Unit)? = null,
    ): Boolean {
        if (entity.position.distance(endpoint) < minimumDistance || entity.position.sameBlock(endpoint)) {
            entity.navigator.reset()
            onComplete?.invoke()
            return true
        }
        return runCatching {
            entity.navigator.setPathTo(
                endpoint,
                minimumDistance,
                (entity.position.distance(endpoint) + 16.0).coerceIn(50.0, MAX_PATH_DISTANCE),
                PATH_VARIANCE,
                onComplete?.let { callback -> Runnable { callback() } },
            )
        }.getOrElse {
            entity.navigator.reset()
            false
        }
    }
}

internal fun navigationMinimumDistance(
    goal: NavigationGoal,
    finalSegment: Boolean,
): Double = if (goal.playerId == null && finalSegment) PATH_MINIMUM_DISTANCE else PLAYER_PATH_MINIMUM_DISTANCE

internal fun flagApproachForAttempt(
    approaches: List<Pos>,
    attempt: Int,
): Pos {
    require(approaches.isNotEmpty()) { "At least one flag approach is required" }
    return approaches[Math.floorMod(attempt, approaches.size)]
}

internal fun hasHorizontalNavigationProgress(
    previous: Pos,
    current: Pos,
): Boolean {
    val deltaX = current.x - previous.x
    val deltaZ = current.z - previous.z
    return deltaX * deltaX + deltaZ * deltaZ >= PATH_PROGRESS_DISTANCE * PATH_PROGRESS_DISTANCE
}

internal fun hasScaffoldClimbProgress(
    previousHighestY: Double,
    currentY: Double,
): Boolean = currentY - previousHighestY >= PATH_PROGRESS_DISTANCE

internal fun hasNavigationProgress(
    previous: Pos?,
    current: Pos,
    scaffoldNavigation: Boolean,
    previousHighestY: Double,
): Boolean {
    if (previous == null) return true
    return if (scaffoldNavigation) {
        hasScaffoldClimbProgress(previousHighestY, current.y)
    } else {
        hasHorizontalNavigationProgress(previous, current)
    }
}

internal fun chooseSafeDirectMovementTarget(
    position: Pos,
    preferredTarget: Pos,
    alternateTarget: Pos?,
    speed: Double,
    canStandAt: (Pos) -> Boolean,
): Pos? = sequenceOf(preferredTarget, alternateTarget)
    .filterNotNull()
    .firstOrNull { target ->
        val movement = playerLikeGroundMovement(position, target, speed)
        movement != Vec.ZERO && canStandAt(position.add(movement))
    }

internal const val PATH_RETRY_MILLIS = 500L
private const val PLAYER_PATH_REFRESH_MILLIS = 250L
private const val PATH_STALL_MILLIS = 900L
private const val SCAFFOLD_PATH_STALL_MILLIS = 2_000L
private const val PATH_GOAL_MOVEMENT_DISTANCE = 1.5
private const val PATH_MINIMUM_DISTANCE = 0.35
private const val PLAYER_PATH_MINIMUM_DISTANCE = 2.0
private const val MAX_PATH_DISTANCE = 80.0
private const val PATH_VARIANCE = 16.0
private const val PATH_PROGRESS_DISTANCE = 0.08
