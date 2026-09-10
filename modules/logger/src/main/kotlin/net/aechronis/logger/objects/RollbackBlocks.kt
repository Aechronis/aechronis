package net.aechronis.logger.objects

import net.aechronis.logger.utils.ItemCodec
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.block.Block

internal data class RollbackBlockPosition(
    val x: Int,
    val y: Int,
    val z: Int,
)

/** Reconstructs persisted block values and compares state, NBT, and handlers consistently. */
internal object RollbackBlocks {
    fun decode(
        state: String?,
        materialKey: String?,
        nbt: ByteArray?,
        handlerKey: String? = null,
    ): Block? {
        val base = state?.let(Block::fromState) ?: materialKey?.let(Block::fromKey) ?: return null
        val withNbt = base.withNbt(ItemCodec.decodeBlockNbt(nbt))
        return handlerKey
            ?.let { MinecraftServer.getBlockManager().getHandlerOrDummy(it) }
            ?.let(withNbt::withHandler)
            ?: withNbt
    }

    fun matches(
        first: Block,
        second: Block,
        handlerKey: String? = null,
    ): Boolean =
        first.state() == second.state() &&
            first.nbt() == second.nbt() &&
            (handlerKey == null || first.handler()?.key?.asString() == handlerKey)
}
