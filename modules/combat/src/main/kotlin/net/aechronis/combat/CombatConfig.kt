package net.aechronis.combat

import net.minestom.server.instance.block.Block

data class CombatConfig(
    /** Blocks that explosions must leave intact. */
    val noBlockExplodeList: List<Block> =
        listOf(
            Block.BARREL,
            Block.BEDROCK,
            Block.OBSIDIAN,
        ),
)
