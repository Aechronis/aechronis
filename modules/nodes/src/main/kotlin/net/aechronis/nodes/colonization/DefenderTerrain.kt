package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.war.Attack
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
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
        !floor.liquid() &&
        !feet.liquid() &&
        !head.liquid()
}

internal fun isClearDefenderStandingPosition(
    instance: Instance,
    position: Pos,
): Boolean {
    val x = position.blockX()
    val y = position.blockY()
    val z = position.blockZ()
    return isSafeDefenderStandingPosition(instance, position) &&
        !instance.getBlock(x, y, z).blocksMotion() &&
        !instance.getBlock(x, y + 1, z).blocksMotion()
}

internal enum class DirectGroundStep {
    LEVEL,
    JUMP_UP,
    DROP_DOWN,
}

// how a defender moving without a path gets from [current] onto [next], or null when it should not go there
internal fun directGroundStep(
    instance: Instance,
    current: Pos,
    next: Pos,
): DirectGroundStep? {
    val x = next.blockX()
    val y = next.blockY()
    val z = next.blockZ()
    val feet = instance.getBlock(x, y, z)
    val head = instance.getBlock(x, y + 1, z)
    if (feet.liquid() || head.liquid() || head.blocksMotion()) return null

    if (feet.blocksMotion()) {
        val rise = y + feet.collisionShape().relativeEnd().y() - next.y
        // nothing to climb when already standing on a slab or stair sharing the feet block
        if (rise <= DIRECT_STEP_EPSILON) return DirectGroundStep.LEVEL
        val canJump = rise <= 1.0 + DIRECT_STEP_EPSILON &&
            !instance.getBlock(x, y + 2, z).blocksMotion() &&
            !instance.getBlock(current.blockX(), y + 2, current.blockZ()).blocksMotion()
        return if (canJump) DirectGroundStep.JUMP_UP else null
    }

    val floor = instance.getBlock(x, y - 1, z)
    if (floor.liquid()) return null
    if (floor.blocksMotion()) return DirectGroundStep.LEVEL
    val belowFloor = instance.getBlock(x, y - 2, z)
    return if (belowFloor.blocksMotion() && !belowFloor.liquid()) DirectGroundStep.DROP_DOWN else null
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
    if (longestAxis < 0.5) return null

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
            val belowFloor = blockAt(belowFloorPosition)
            val isWalkableDrop = !floorBlock.liquid() && belowFloor.blocksMotion() && !belowFloor.liquid()
            if (!isWalkableDrop) {
                if (canEdit(floorPosition)) return AiTerrainEdit(AiTerrainEditKind.PLACE, floorPosition)
                return null
            }
        }
    }
    return null
}

internal fun canBreakTerrain(
    block: Block,
    position: BlockVec,
    canEdit: (BlockVec) -> Boolean,
): Boolean = block.blocksMotion() && block.registry()?.hardness() != -1F && canEdit(position)

internal fun hasSolidFace(
    instance: Instance,
    position: BlockVec,
): Boolean = listOf(
    position.add(1, 0, 0),
    position.add(-1, 0, 0),
    position.add(0, 1, 0),
    position.add(0, -1, 0),
    position.add(0, 0, 1),
    position.add(0, 0, -1),
).any { neighbour -> instance.isChunkLoaded(neighbour) && instance.getBlock(neighbour).blocksMotion() }

// the same limits a vanilla client works within: a face to click, within reach, with nobody standing in the block
internal fun canPlaceLikeVanilla(
    instance: Instance,
    placer: LivingEntity,
    position: BlockVec,
): Boolean {
    if (instance.getBlock(position).blocksMotion() || !hasSolidFace(instance, position)) return false
    val center = position.add(0.5, 0.5, 0.5).asPos()
    val eyes = placer.position.add(0.0, placer.eyeHeight, 0.0)
    if (eyes.distanceSquared(center) > BLOCK_PLACE_REACH * BLOCK_PLACE_REACH) return false
    return instance.getNearbyEntities(center, ENTITY_OCCUPANCY_SEARCH).none { entity ->
        entity is LivingEntity && (entity as? Player)?.gameMode != GameMode.SPECTATOR && occupiesBlock(entity, position)
    }
}

private fun occupiesBlock(
    entity: Entity,
    position: BlockVec,
): Boolean {
    val halfWidth = entity.boundingBox.width() / 2
    val halfDepth = entity.boundingBox.depth() / 2
    val feet = entity.position
    return feet.x - halfWidth < position.blockX + 1 &&
        feet.x + halfWidth > position.blockX &&
        feet.z - halfDepth < position.blockZ + 1 &&
        feet.z + halfDepth > position.blockZ &&
        feet.y < position.blockY + 1 &&
        feet.y + entity.boundingBox.height() > position.blockY
}

private const val TERRAIN_EDIT_REACH = 4.0
private const val BLOCK_PLACE_REACH = 4.5
private const val ENTITY_OCCUPANCY_SEARCH = 3.0
private const val DIRECT_STEP_EPSILON = 0.001
internal const val MINIMUM_ELEVATION_TO_BUILD = 2
