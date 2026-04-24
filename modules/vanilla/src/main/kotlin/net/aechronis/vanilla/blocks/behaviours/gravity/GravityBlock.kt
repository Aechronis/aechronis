package net.aechronis.vanilla.blocks.behaviours.gravity

import net.aechronis.vanilla.blocks.BlockBehaviour
import net.aechronis.vanilla.blocks.Blocks
import net.aechronis.vanilla.blocks.updates.BlockUpdateInfo
import net.aechronis.vanilla.blocks.updates.BlockUpdates
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block

class GravityBlock(
    block: Block,
) : BlockBehaviour(block),
    BlockUpdates {
    override fun update(
        instance: Instance,
        pos: Pos,
        info: BlockUpdateInfo,
    ) {
        TODO("Not yet implemented")
    }

    fun checkFall(
        instance: Instance,
        position: Point,
        block: Block?,
    ): Boolean {
        val below = instance.getBlock(position.blockX(), position.blockY() - 1, position.blockZ())

        if (below.isSolid()) {
            return false
        }

        return true
    }
}
