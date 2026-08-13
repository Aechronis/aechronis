package net.aechronis.watchdog.checks

import net.minestom.server.MinecraftServer
import net.minestom.server.collision.BoundingBox
import net.minestom.server.collision.ShapeImpl
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockTags
import kotlin.math.floor

internal object CollisionOracle {
    fun crossesSolid(
        player: Player,
        from: Pos,
        to: Pos,
    ): Boolean {
        val distance = from.distance(to)
        val steps = (distance / SWEEP_STEP).toInt().coerceAtMost(MAX_SWEEP_STEPS)
        if (steps < 2) return false
        for (step in 1 until steps) {
            val fraction = step.toDouble() / steps
            val position =
                Pos(
                    from.x + (to.x - from.x) * fraction,
                    from.y + (to.y - from.y) * fraction,
                    from.z + (to.z - from.z) * fraction,
                    to.yaw,
                    to.pitch,
                )
            val snapshot = sample(player, position)
            if (snapshot.loaded && snapshot.insideSolid) return true
        }
        return false
    }

    fun sample(
        player: Player,
        position: Pos,
    ): CollisionSnapshot {
        val instance = player.instance ?: return unloaded()
        val playerBox = player.boundingBox
        val minX = floor(position.x + playerBox.minX()).toInt()
        val maxX = floor(position.x + playerBox.maxX() - EPSILON).toInt()
        val minY = floor(position.y + playerBox.minY()).toInt()
        val maxY = floor(position.y + playerBox.maxY() - EPSILON).toInt()
        val minZ = floor(position.z + playerBox.minZ()).toInt()
        val maxZ = floor(position.z + playerBox.maxZ() - EPSILON).toInt()
        val supportMinY = floor(position.y + playerBox.minY() - SUPPORT_TOLERANCE).toInt()
        val supportMaxY = floor(position.y + playerBox.minY() + SUPPORT_TOLERANCE).toInt()

        if (!loaded(instance, minX, maxX, minZ, maxZ)) return unloaded()

        val absolutePlayerBox =
            Box(
                position.x + playerBox.minX(),
                position.y + playerBox.minY(),
                position.z + playerBox.minZ(),
                position.x + playerBox.maxX(),
                position.y + playerBox.maxY(),
                position.z + playerBox.maxZ(),
            )
        var insideSolid = false
        var inLiquid = false
        var climbable = false

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val block = instance.getBlock(x, y, z)
                    if (block.isLiquid) inLiquid = true
                    if (isClimbable(block)) climbable = true
                    if (!block.blocksMotion()) continue
                    if (collides(block, x, y, z, absolutePlayerBox, EPSILON)) {
                        insideSolid = true
                    }
                }
            }
        }

        val feet = absolutePlayerBox.minY
        var supported = false
        var belowLiquid = false
        for (x in minX..maxX) {
            for (y in supportMinY..supportMaxY) {
                for (z in minZ..maxZ) {
                    val block = instance.getBlock(x, y, z)
                    if (block.isLiquid && y <= floor(feet).toInt()) belowLiquid = true
                    if (!supported && supports(block, x, y, z, absolutePlayerBox, feet)) {
                        supported = true
                    }
                }
            }
        }

        return CollisionSnapshot(true, supported, insideSolid, inLiquid, belowLiquid, climbable)
    }

    private fun loaded(
        instance: net.minestom.server.instance.Instance,
        minX: Int,
        maxX: Int,
        minZ: Int,
        maxZ: Int,
    ): Boolean {
        for (chunkX in (minX shr 4)..(maxX shr 4)) {
            for (chunkZ in (minZ shr 4)..(maxZ shr 4)) {
                if (!instance.isChunkLoaded(chunkX, chunkZ)) return false
            }
        }
        return true
    }

    private fun supports(
        block: Block,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
        playerBox: Box,
        feet: Double,
    ): Boolean {
        if (block.isAir || block.isLiquid) return false
        return collisionBoxes(block).any { shape ->
            val absolute = absolute(shape, blockX, blockY, blockZ)
            absDifference(absolute.maxY, feet) <= SUPPORT_TOLERANCE &&
                absolute.maxY <= feet + SUPPORT_TOLERANCE &&
                horizontalOverlap(playerBox, absolute)
        }
    }

    private fun collides(
        block: Block,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
        playerBox: Box,
        epsilon: Double,
    ): Boolean =
        collisionBoxes(block).any { shape ->
            overlaps(playerBox, absolute(shape, blockX, blockY, blockZ), epsilon)
        }

    private fun collisionBoxes(block: Block): List<BoundingBox> {
        val shape = block.registry()?.collisionShape() ?: return emptyList()
        return when (shape) {
            is ShapeImpl -> shape.boundingBoxes()
            is BoundingBox -> listOf(shape)
            else -> listOf(BoundingBox(shape.relativeStart().asVec(), shape.relativeEnd().asVec()))
        }
    }

    private fun absolute(
        box: BoundingBox,
        x: Int,
        y: Int,
        z: Int,
    ): Box =
        Box(
            x + box.minX(),
            y + box.minY(),
            z + box.minZ(),
            x + box.maxX(),
            y + box.maxY(),
            z + box.maxZ(),
        )

    private fun horizontalOverlap(
        first: Box,
        second: Box,
    ): Boolean =
        first.minX < second.maxX - EPSILON &&
            first.maxX > second.minX + EPSILON &&
            first.minZ < second.maxZ - EPSILON &&
            first.maxZ > second.minZ + EPSILON

    private fun overlaps(
        first: Box,
        second: Box,
        epsilon: Double,
    ): Boolean =
        first.minX < second.maxX - epsilon &&
            first.maxX > second.minX + epsilon &&
            first.minY < second.maxY - epsilon &&
            first.maxY > second.minY + epsilon &&
            first.minZ < second.maxZ - epsilon &&
            first.maxZ > second.minZ + epsilon

    private fun isClimbable(block: Block): Boolean {
        val registry = MinecraftServer.process().blocks()
        val key = registry.getKey(block) ?: return false
        return registry.getTag(BlockTags.CLIMBABLE)?.contains(key) == true ||
            block
                .registry()
                ?.key()
                ?.value()
                ?.let { it.contains("ladder") || it.contains("vine") || it.contains("scaffolding") } == true
    }

    private fun absDifference(
        first: Double,
        second: Double,
    ): Double = kotlin.math.abs(first - second)

    private fun unloaded() = CollisionSnapshot(false, false, false, false, false, false)

    private data class Box(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
    )

    private const val EPSILON = 1.0E-7
    private const val SUPPORT_TOLERANCE = 0.08
    private const val SWEEP_STEP = 0.25
    private const val MAX_SWEEP_STEPS = 32
}
