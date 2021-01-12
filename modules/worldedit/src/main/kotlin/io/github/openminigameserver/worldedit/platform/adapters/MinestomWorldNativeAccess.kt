package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.jnbt.CompoundTag
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.internal.wna.WorldNativeAccess
import com.sk89q.worldedit.world.block.BlockState
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.batch.BlockBatch
import net.minestom.server.utils.Position
import java.lang.ref.WeakReference

class MinestomWorldNativeAccess(private val worldRef: WeakReference<Instance>, val useBlockBatch: Boolean) :
    WorldNativeAccess<Chunk, Short, Position> {

    private var currentBlockBatch = newBlockBatch()

    private fun newBlockBatch(): BlockBatch? = if (!useBlockBatch) null else getWorld().createBlockBatch()

    private fun getWorld(): Instance {
        return worldRef.get() ?: throw RuntimeException("World is unloaded")
    }

    override fun getChunk(x: Int, z: Int): Chunk? {
        return getWorld().getChunk(x, z)
    }

    override fun toNative(state: BlockState): Short {
        return BlockStateIdAccess.getBlockStateId(state).toShort()
    }

    override fun getBlockState(chunk: Chunk, position: Position): Short {
        val pos = position.toBlockPosition()
        return chunk.getBlockStateId(pos.x, pos.y, pos.z)
    }

    override fun setBlockState(chunk: Chunk, position: Position, state: Short): Short {
        return if (useBlockBatch && currentBlockBatch != null) {
            currentBlockBatch!!.setBlockStateId(position.toBlockPosition(), state).let { state }
        } else {
            getWorld().setBlockStateId(position.toBlockPosition(), state).let { state }
        }
    }


    override fun getPosition(x: Int, y: Int, z: Int): Position {
        return Position(x.toFloat(), y.toFloat(), z.toFloat())
    }

    override fun getValidBlockForPosition(block: Short, position: Position?): Short {
        return block
    }

    override fun updateLightingForBlock(position: Position?) {
    }

    override fun updateTileEntity(position: Position?, tag: CompoundTag?): Boolean {
        //TODO
        return false
    }

    override fun notifyBlockUpdate(position: Position?, oldState: Short?, newState: Short?) {
    }

    override fun isChunkTicking(chunk: Chunk?): Boolean {
        return chunk != null
    }

    override fun markBlockChanged(position: Position?) {
    }

    override fun notifyNeighbors(pos: Position?, oldState: Short?, newState: Short?) {
    }

    override fun updateNeighbors(pos: Position?, oldState: Short?, newState: Short?, recursionLimit: Int) {
    }

    override fun onBlockStateChange(pos: Position?, oldState: Short?, newState: Short?) {
    }

    fun flush() {
        currentBlockBatch?.flush {}
    }

}