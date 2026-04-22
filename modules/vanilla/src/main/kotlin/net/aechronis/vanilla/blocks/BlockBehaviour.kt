package net.aechronis.vanilla.blocks

import net.kyori.adventure.key.Key
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler

abstract class BlockBehaviour(protected val block: Block) : BlockHandler {
    override fun getKey(): Key = block.key()
}
