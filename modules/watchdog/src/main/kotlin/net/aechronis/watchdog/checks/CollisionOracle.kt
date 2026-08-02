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
        val x = floor(position.x).toInt()
        val y = floor(position.y).toInt()
        val z = floor(position.z).toInt()
        if (!instance.isChunkLoaded(x shr 4, z shr 4)) {
            return CollisionSnapshot(false, false, false, false, false)
        }

        val belowBlocks =
            buildList {
                for (blockX in x - 1..x + 1) {
                    for (blockZ in z - 1..z + 1) {
                        add(instance.getBlock(blockX, y - 1, blockZ, Block.Getter.Condition.TYPE))
                    }
                }
            }
        val bodyBlocks =
            buildList {
                for (blockX in x - 1..x + 1) {
                    for (blockZ in z - 1..z + 1) {
                        add(instance.getBlock(blockX, y, blockZ, Block.Getter.Condition.TYPE))
                        add(instance.getBlock(blockX, y + 1, blockZ, Block.Getter.Condition.TYPE))
                    }
                }
            }
        val below = belowBlocks.filter { it != null && !it.isAir }
        val belowLiquid = below.any { it!!.isLiquid }
        val inside = bodyBlocks.any { it != null && it.isSolid && it != Block.AIR }
        val liquid = bodyBlocks.any { it != null && it.isLiquid }
        val supported = below.any { it!!.blocksMotion() }
        return CollisionSnapshot(true, supported, inside, liquid, belowLiquid)
    }
}
