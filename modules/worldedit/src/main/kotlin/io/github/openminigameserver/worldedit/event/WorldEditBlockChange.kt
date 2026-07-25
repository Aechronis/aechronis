package io.github.openminigameserver.worldedit.event

import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.block.Block

data class WorldEditBlockChange(
    val position: BlockVec,
    val oldBlock: Block,
    val newBlock: Block,
)
