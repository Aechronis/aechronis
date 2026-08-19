package net.aechronis.combat.events

import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.block.Block

enum class ExplosionBlockChangeType {
    DAMAGE,
    FIRE,
}

data class ExplosionBlockChange(
    val position: BlockVec,
    val oldBlock: Block,
    val newBlock: Block,
    val type: ExplosionBlockChangeType,
)
