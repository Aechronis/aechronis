package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.internal.wna.WorldNativeAccess
import com.sk89q.worldedit.world.block.BlockState
import io.github.openminigameserver.worldedit.event.WorldEditBlockChange
import io.github.openminigameserver.worldedit.event.WorldEditBlockChangesEvent
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.batch.AbsoluteBlockBatch
import net.minestom.server.instance.block.Block
import org.enginehub.linbus.tree.LinCompoundTag
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture

class MinestomWorldNativeAccess(
    private val worldRef: WeakReference<Instance>,
    val useBlockBatch: Boolean,
) : WorldNativeAccess<Chunk, Block, Pos> {
    var actor: Actor? = null

    private var currentBlockBatch = newBlockBatch()
    private var hasPendingChanges = false
    private var pendingBlockCount = 0
    private val pendingChanges = LinkedHashMap<BlockVec, WorldEditBlockChange>()

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
        val world = getWorld()
        val x = position.blockX()
        val y = position.blockY()
        val z = position.blockZ()
        val oldState = world.getBlock(x, y, z)
        val batch = currentBlockBatch
        if (useBlockBatch && batch != null) {
            batch.setBlock(x, y, z, state)
            recordPendingChange(x, y, z, oldState, state)
            hasPendingChanges = true
            pendingBlockCount++
            if (pendingBlockCount >= MAX_BLOCKS_PER_BATCH) {
                flush().join()
            }
        } else {
            val currentActor = actor
            world.setBlock(x, y, z, state)
            dispatchChanges(
                currentActor,
                world,
                listOf(WorldEditBlockChange(BlockVec(x, y, z), oldState, state)),
            )
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

    fun flush(): CompletableFuture<Unit> {
        val batch = currentBlockBatch
        currentBlockBatch = newBlockBatch()
        val changes = pendingChanges.values.toList()
        val currentActor = actor
        pendingChanges.clear()

        if (!useBlockBatch || batch == null || !hasPendingChanges) {
            hasPendingChanges = false
            pendingBlockCount = 0
            return CompletableFuture.completedFuture(Unit)
        }

        hasPendingChanges = false
        pendingBlockCount = 0
        val completion = CompletableFuture<Unit>()
        val world = getWorld()
        batch.unsafeApply(world) {
            try {
                dispatchChanges(currentActor, world, changes)
                completion.complete(Unit)
            } catch (throwable: Throwable) {
                completion.completeExceptionally(throwable)
            }
        }
        return completion
    }

    private fun recordPendingChange(
        x: Int,
        y: Int,
        z: Int,
        oldState: Block,
        newState: Block,
    ) {
        if (actor == null) return
        val position = BlockVec(x, y, z)
        val previous = pendingChanges[position]
        val originalState = previous?.oldBlock ?: oldState
        if (sameState(originalState, newState)) {
            pendingChanges.remove(position)
        } else {
            pendingChanges[position] = WorldEditBlockChange(position, originalState, newState)
        }
    }

    private fun dispatchChanges(
        actor: Actor?,
        world: Instance,
        changes: List<WorldEditBlockChange>,
    ) {
        if (actor == null || changes.isEmpty()) return
        val effectiveChanges = changes.filterNot { sameState(it.oldBlock, it.newBlock) }
        if (effectiveChanges.isEmpty()) return

        MinecraftServer.getGlobalEventHandler().call(
            WorldEditBlockChangesEvent(
                actorUuid = actor.uniqueId,
                actorName = actor.name,
                instance = world,
                changes = effectiveChanges,
            ),
        )
    }

    private fun sameState(
        first: Block,
        second: Block,
    ): Boolean = first.stateId() == second.stateId() && first.nbt() == second.nbt()

    private companion object {
        const val MAX_BLOCKS_PER_BATCH = 32_768
    }
}
