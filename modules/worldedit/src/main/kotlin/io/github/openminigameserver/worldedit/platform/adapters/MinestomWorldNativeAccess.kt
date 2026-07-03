package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.internal.wna.WorldNativeAccess
import com.sk89q.worldedit.world.block.BlockState
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.batch.AbsoluteBlockBatch
import net.minestom.server.instance.block.Block
import org.enginehub.linbus.tree.LinCompoundTag
import java.lang.ref.WeakReference

class MinestomWorldNativeAccess(
    private val worldRef: WeakReference<Instance>,
    val useBlockBatch: Boolean,
) : WorldNativeAccess<Chunk, Block, Pos> {
    private var currentBlockBatch = newBlockBatch()

    private fun newBlockBatch(): AbsoluteBlockBatch? = if (!useBlockBatch) null else AbsoluteBlockBatch()

    private fun getWorld(): Instance = worldRef.get() ?: throw RuntimeException("World is unloaded")

    override fun getChunk(
        x: Int,
        z: Int,
    ): Chunk = getWorld().getChunk(x, z) ?: throw RuntimeException("Chunk $x,$z is not loaded")

    override fun toNative(state: BlockState): Block {
        val stateId = BlockStateIdAccess.getBlockStateId(state)
        return Block.fromStateId(stateId) ?: Block.AIR
    }

    override fun getBlockState(
        chunk: Chunk,
        position: Pos,
    ): Block = getWorld().getBlock(position.blockX(), position.blockY(), position.blockZ())

    override fun setBlockState(
        chunk: Chunk,
        position: Pos,
        state: Block,
    ): Block {
        val batch = currentBlockBatch
        if (useBlockBatch && batch != null) {
            batch.setBlock(position.blockX(), position.blockY(), position.blockZ(), state)
        } else {
            getWorld().setBlock(position.blockX(), position.blockY(), position.blockZ(), state)
        }
        return state
    }

    override fun getPosition(
        x: Int,
        y: Int,
        z: Int,
    ): Pos = Pos(x.toDouble(), y.toDouble(), z.toDouble())

    override fun getValidBlockForPosition(
        block: Block,
        position: Pos,
    ): Block = block

    override fun updateLightingForBlock(position: Pos) {
    }

    override fun updateTileEntity(
        position: Pos,
        tag: LinCompoundTag,
    ): Boolean {
        // TODO
        return false
    }

    override fun notifyBlockUpdate(
        chunk: Chunk,
        position: Pos,
        oldState: Block,
        newState: Block,
    ) {
    }

    override fun isChunkTicking(chunk: Chunk): Boolean = chunk.isLoaded

    override fun markBlockChanged(
        chunk: Chunk,
        position: Pos,
    ) {
    }

    override fun notifyNeighbors(
        pos: Pos,
        oldState: Block,
        newState: Block,
    ) {
    }

    override fun updateNeighbors(
        pos: Pos,
        oldState: Block,
        newState: Block,
        recursionLimit: Int,
    ) {
    }

    override fun onBlockStateChange(
        pos: Pos,
        oldState: Block,
        newState: Block,
    ) {
    }

    fun flush() {
        currentBlockBatch?.apply(getWorld()) { }
    }
}
