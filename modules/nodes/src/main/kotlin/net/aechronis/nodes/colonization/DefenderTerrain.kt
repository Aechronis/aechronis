package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.war.Attack
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal enum class AiTerrainEditKind {
    BREAK,
    PLACE,
}

internal data class AiTerrainEdit(
    val kind: AiTerrainEditKind,
    val position: BlockVec,
)

internal fun attackProtectedBlocks(attack: Attack): Set<BlockVec> = buildSet {
    add(attack.flagBase)
    add(attack.flagBlock)
    add(attack.flagTorch)
    addAll(attack.skyBeaconColorBlocks)
    addAll(attack.skyBeaconWireframeBlocks)
}

internal fun flagApproachCandidates(
    instance: Instance,
    attack: Attack,
    eyeHeight: Double,
    flagReach: Double,
): List<Pos> {
    val flagPosition = attack.flagBlock.add(0.5, 0.5, 0.5).asPos()
    val dimension = instance.cachedDimensionType
    val minimumY = (attack.flagBase.blockY - 2).coerceAtLeast(dimension.minY() + 1)
    val maximumY = (attack.flagBase.blockY + 2).coerceAtMost(dimension.maxY() - 2)
    val targetTown = attack.targetTown
    val protectedBlocks = attackProtectedBlocks(attack)
    return buildList {
        for (y in minimumY..maximumY) {
            for (x in attack.flagBlock.blockX - 2..attack.flagBlock.blockX + 2) {
                for (z in attack.flagBlock.blockZ - 2..attack.flagBlock.blockZ + 2) {
                    val candidate = Pos(x + 0.5, y.toDouble(), z + 0.5)
                    if (!instance.worldBorder.inBounds(candidate)) continue
                    if (targetTown != null && Territory.fromBlock(x, z)?.town !== targetTown) continue
                    val eyePosition = candidate.add(0.0, eyeHeight, 0.0)
                    if (eyePosition.distanceSquared(flagPosition) > flagReach * flagReach) continue
                    val feet = BlockVec(x, y, z)
                    if (feet in protectedBlocks || feet.add(0, 1, 0) in protectedBlocks) continue
                    add(candidate)
                }
            }
        }
    }
}

internal fun isSafeDefenderStandingPosition(
    instance: Instance,
    position: Pos,
): Boolean {
    val x = position.blockX()
    val y = position.blockY()
    val z = position.blockZ()
    val floor = instance.getBlock(x, y - 1, z)
    val feet = instance.getBlock(x, y, z)
    val head = instance.getBlock(x, y + 1, z)
    return floor.blocksMotion() &&
        !floor.isLiquid &&
        !feet.blocksMotion() &&
        !feet.isLiquid &&
        !head.blocksMotion() &&
        !head.isLiquid
}

// chooses one nearby terrain edit that opens the direct route from [start] toward [target]
// a one block step or drop is already walkable so left untouched
internal fun aiTerrainEdit(
    start: Pos,
    target: Pos,
    blockAt: (BlockVec) -> Block,
    canEdit: (BlockVec) -> Boolean = { true },
): AiTerrainEdit? {
    val deltaX = target.x - start.x
    val deltaZ = target.z - start.z
    val longestAxis = max(abs(deltaX), abs(deltaZ))
    val needsElevation = target.blockY() > start.blockY()
    if (longestAxis < 0.5) {
        if (!needsElevation) return null
        val adjacentColumns = listOf(
            start.blockX() + 1 to start.blockZ(),
            start.blockX() - 1 to start.blockZ(),
            start.blockX() to start.blockZ() + 1,
            start.blockX() to start.blockZ() - 1,
        )
        return adjacentColumns.firstNotNullOfOrNull { (x, z) ->
            elevatedStepPlacement(x, start.blockY(), z, blockAt, canEdit)
        }
    }

    val sampleCount = minOf(ceil(longestAxis).toInt(), floor(TERRAIN_EDIT_REACH).toInt())
    var previousColumn: Pair<Int, Int>? = null
    for (sample in 1..sampleCount) {
        val progress = (sample / longestAxis).coerceAtMost(1.0)
        val x = floor(start.x + deltaX * progress).toInt()
        val z = floor(start.z + deltaZ * progress).toInt()
        val column = x to z
        if (column == previousColumn || (x == start.blockX() && z == start.blockZ())) continue
        previousColumn = column

        val feetPosition = BlockVec(x, start.blockY(), z)
        val floorPosition = feetPosition.add(0, -1, 0)
        val headPosition = feetPosition.add(0, 1, 0)
        val abovePosition = feetPosition.add(0, 2, 0)
        val belowFloorPosition = feetPosition.add(0, -2, 0)
        val feet = blockAt(feetPosition)
        val head = blockAt(headPosition)
        val above = blockAt(abovePosition)

        if (feet.blocksMotion()) {
            val breakPosition = when {
                head.blocksMotion() && !above.blocksMotion() -> headPosition
                head.blocksMotion() -> feetPosition
                above.blocksMotion() -> feetPosition
                else -> null
            }
            if (breakPosition != null && canBreakTerrain(blockAt(breakPosition), breakPosition, canEdit)) {
                return AiTerrainEdit(AiTerrainEditKind.BREAK, breakPosition)
            }
            if (breakPosition != null) return null
            continue
        }

        if (head.blocksMotion()) {
            if (canBreakTerrain(head, headPosition, canEdit)) {
                return AiTerrainEdit(AiTerrainEditKind.BREAK, headPosition)
            }
            return null
        }

        val floorBlock = blockAt(floorPosition)
        if (!floorBlock.blocksMotion()) {
            if (needsElevation) {
                elevatedStepPlacement(x, start.blockY(), z, blockAt, canEdit)?.let { return it }
            }
            val belowFloor = blockAt(belowFloorPosition)
            val isWalkableDrop = !floorBlock.isLiquid && belowFloor.blocksMotion() && !belowFloor.isLiquid
            if (!isWalkableDrop) {
                if (canEdit(floorPosition)) return AiTerrainEdit(AiTerrainEditKind.PLACE, floorPosition)
                return null
            }
        } else if (needsElevation) {
            elevatedStepPlacement(x, start.blockY(), z, blockAt, canEdit)?.let { return it }
        }
    }
    return null
}

private fun elevatedStepPlacement(
    x: Int,
    feetY: Int,
    z: Int,
    blockAt: (BlockVec) -> Block,
    canEdit: (BlockVec) -> Boolean,
): AiTerrainEdit? {
    val placement = BlockVec(x, feetY, z)
    if (blockAt(placement).blocksMotion() || !canEdit(placement)) return null
    if (blockAt(placement.add(0, 1, 0)).blocksMotion()) return null
    if (blockAt(placement.add(0, 2, 0)).blocksMotion()) return null
    return AiTerrainEdit(AiTerrainEditKind.PLACE, placement)
}

private fun canBreakTerrain(
    block: Block,
    position: BlockVec,
    canEdit: (BlockVec) -> Boolean,
): Boolean = block.blocksMotion() && block.registry()?.hardness() != -1F && canEdit(position)

private const val TERRAIN_EDIT_REACH = 4.0
