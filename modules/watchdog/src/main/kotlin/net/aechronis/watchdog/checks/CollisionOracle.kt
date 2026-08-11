package net.aechronis.watchdog.checks

import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import kotlin.math.floor

internal object CollisionOracle {
    fun sample(
        player: Player,
        position: Pos,
    ): CollisionSnapshot {
        val instance = player.instance ?: return CollisionSnapshot(false, false, false, false, false)
        val minChunkX = floor(position.x + player.boundingBox.minX()).toInt() shr 4
        val maxChunkX = floor(position.x + player.boundingBox.maxX()).toInt() shr 4
        val minChunkZ = floor(position.z + player.boundingBox.minZ()).toInt() shr 4
        val maxChunkZ = floor(position.z + player.boundingBox.maxZ()).toInt() shr 4
        if (!instance.isChunkLoaded(minChunkX, minChunkZ) || !instance.isChunkLoaded(maxChunkX, maxChunkZ)) {
            return CollisionSnapshot(false, false, false, false, false)
        }

        val box = player.boundingBox
        val minX = floor(position.x + box.minX()).toInt()
        val maxX = floor(position.x + box.maxX() - EPSILON).toInt()
        val minY = floor(position.y + box.minY()).toInt()
        val maxY = floor(position.y + box.maxY() - EPSILON).toInt()
        val minZ = floor(position.z + box.minZ()).toInt()
        val maxZ = floor(position.z + box.maxZ() - EPSILON).toInt()

        var insideSolid = false
        var inLiquid = false
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val block = instance.getBlock(x, y, z, Block.Getter.Condition.TYPE)
                    if (block != null && block.isSolid) insideSolid = true
                    if (block != null && block.isLiquid) inLiquid = true
                }
            }
        }
        val supportY = floor(position.y - EPSILON).toInt() - 1
        var supported = false
        var belowLiquid = false
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val block = instance.getBlock(x, supportY, z, Block.Getter.Condition.TYPE)
                if (block != null && block.blocksMotion()) supported = true
                if (block != null && block.isLiquid) belowLiquid = true
            }
        }

        return CollisionSnapshot(true, supported, insideSolid, inLiquid, belowLiquid)
    }

    private const val EPSILON = 1.0E-4
}
