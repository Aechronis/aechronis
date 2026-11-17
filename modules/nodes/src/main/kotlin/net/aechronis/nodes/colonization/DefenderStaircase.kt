package net.aechronis.nodes.colonization

import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import kotlin.math.hypot

private const val CLIMB_SEGMENT_STEPS = 6
private const val MAX_TOP_STEP_FLAG_DISTANCE = 2.5
private const val GROUND_SEARCH_ABOVE = 3
private const val GROUND_SEARCH_BELOW = 8

// the flag's own column is the middle of the first ring, and is never part of any of these rings
private val RING_CENTER_OFFSETS = listOf(
    0 to 0,
    2 to 0,
    -2 to 0,
    0 to 2,
    0 to -2,
    2 to 2,
    2 to -2,
    -2 to 2,
    -2 to -2,
)

/**
 * A spiral of steps around a flag, one level per step and only ever moving along X or Z so the pathfinder can walk it.
 *
 * Which steps exist is read from the world, so any defender can use it and a broken step is simply built again.
 */
internal class DefenderStaircase(
    private val ring: List<Pair<Int, Int>>,
    private val startIndex: Int,
    private val baseY: Int,
    val topStep: Int,
) {
    var plannedAtMillis: Long = 0
    var nextPlacementAtMillis: Long = 0

    /** Step 0 is the existing ground that the staircase starts from. */
    fun tread(step: Int): BlockVec {
        val (x, z) = ring[(startIndex + step) % ring.size]
        return BlockVec(x, baseY + step, z)
    }

    fun support(step: Int): BlockVec = tread(step).add(0, -1, 0)

    fun standingPosition(step: Int): Pos = tread(step).add(0.5, 1.0, 0.5).asPos()

    fun isLoaded(instance: Instance): Boolean = ring.all { (x, z) -> instance.isChunkLoaded(x shr 4, z shr 4) }

    // terrain already standing where a step belongs only counts once there is room to jump onto it
    fun lastIntactStep(instance: Instance): Int {
        for (step in 1..topStep) {
            val usable = instance.getBlock(tread(step)).blocksMotion() &&
                clearanceFor(step).none { position -> instance.getBlock(position).blocksMotion() }
            if (!usable) return step - 1
        }
        return topStep
    }

    fun stepAt(position: Pos): Int? = (0..topStep).firstOrNull { step ->
        val tread = tread(step)
        position.blockX() == tread.blockX &&
            position.blockZ() == tread.blockZ &&
            position.y - tread.blockY in 0.4..1.4
    }

    // short hops up the steps, because the pathfinder rejects a long route that winds around the flag
    fun nextStandingTarget(
        instance: Instance,
        position: Pos,
    ): Pos {
        if (!isLoaded(instance)) return standingPosition(0)
        val lastIntact = lastIntactStep(instance)
        val current = stepAt(position)?.takeIf { step -> step <= lastIntact } ?: return standingPosition(0)
        return standingPosition(minOf(current + CLIMB_SEGMENT_STEPS, lastIntact))
    }

    /**
     * The blocks that must be clear to jump from the step below onto [step]. A jump carries the head past the
     * standing room, so a block right above it would stop a defender from moving across onto the step.
     */
    fun clearanceFor(step: Int): List<BlockVec> {
        val tread = tread(step)
        val previous = tread(step - 1)
        return listOf(tread.add(0, 1, 0), tread.add(0, 2, 0), tread.add(0, 3, 0), previous.add(0, 3, 0))
    }

    fun horizontalDistanceToTop(flagBase: BlockVec): Double {
        val top = tread(topStep)
        return hypot((top.blockX - flagBase.blockX).toDouble(), (top.blockZ - flagBase.blockZ).toDouble())
    }
}

internal fun planDefenderStaircase(
    instance: Instance,
    flagBase: BlockVec,
    builderPosition: Pos,
    eyeHeight: Double,
    canEdit: (BlockVec) -> Boolean,
): DefenderStaircase? {
    // standing on this step keeps the flag within reach
    val topTreadY = flagBase.blockY - 2
    val flagPosition = flagBase.add(0.5, 1.5, 0.5).asPos()
    val staircasesByRing = RING_CENTER_OFFSETS.map { (offsetX, offsetZ) ->
        val ring = squareRing(flagBase.blockX + offsetX, flagBase.blockZ + offsetZ)
        if (ring.any { (x, z) -> !canEdit(BlockVec(x, topTreadY, z)) }) return@map emptyList()
        ring.indices
            .mapNotNull { startIndex ->
                val (x, z) = ring[startIndex]
                val groundY = standableGroundY(instance, x, z, builderPosition.blockY()) ?: return@mapNotNull null
                val topStep = topTreadY - groundY
                if (topStep <= 0) return@mapNotNull null
                DefenderStaircase(ring, startIndex, groundY, topStep)
            }.filter { staircase -> staircase.horizontalDistanceToTop(flagBase) <= MAX_TOP_STEP_FLAG_DISTANCE }
    }
    val closestStart: (List<DefenderStaircase>) -> DefenderStaircase? = { staircases ->
        staircases.minByOrNull { staircase -> builderPosition.distanceSquared(staircase.standingPosition(0)) }
    }
    // a top step diagonal to the flag is close enough but looks at its corner, which is not a clear reach
    val reachesFlag: (DefenderStaircase) -> Boolean = { staircase ->
        val eyes = staircase.standingPosition(staircase.topStep).add(0.0, eyeHeight, 0.0)
        hasClearFlagReach(eyes, flagPosition, instance)
    }
    return staircasesByRing.firstNotNullOfOrNull { staircases -> closestStart(staircases.filter(reachesFlag)) }
        ?: staircasesByRing.firstNotNullOfOrNull(closestStart)
}

// every neighbouring pair of cells shares a side, including the last and first
private fun squareRing(
    centerX: Int,
    centerZ: Int,
): List<Pair<Int, Int>> = listOf(
    centerX - 1 to centerZ - 1,
    centerX to centerZ - 1,
    centerX + 1 to centerZ - 1,
    centerX + 1 to centerZ,
    centerX + 1 to centerZ + 1,
    centerX to centerZ + 1,
    centerX - 1 to centerZ + 1,
    centerX - 1 to centerZ,
)

private fun standableGroundY(
    instance: Instance,
    x: Int,
    z: Int,
    referenceY: Int,
): Int? {
    for (feetY in referenceY + GROUND_SEARCH_ABOVE downTo referenceY - GROUND_SEARCH_BELOW) {
        if (isClearDefenderStandingPosition(instance, Pos(x + 0.5, feetY.toDouble(), z + 0.5))) return feetY - 1
    }
    return null
}
