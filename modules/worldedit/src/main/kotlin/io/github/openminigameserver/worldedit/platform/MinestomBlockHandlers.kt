package io.github.openminigameserver.worldedit.platform

import net.minestom.server.MinecraftServer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler

object MinestomBlockHandlers {
    fun defaultFor(block: Block): BlockHandler? = MinecraftServer.getBlockManager().getHandler(block.key().asString())

    fun resolve(
        block: Block,
        explicitHandler: BlockHandler? = null,
    ): BlockHandler? = explicitHandler ?: block.handler() ?: defaultFor(block)
}
