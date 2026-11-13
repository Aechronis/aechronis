package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.internal.wna.WorldNativeAccess
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.SideEffectSet
import com.sk89q.worldedit.world.block.BaseBlock
import com.sk89q.worldedit.world.block.BlockState
import com.sk89q.worldedit.world.block.BlockStateHolder
import io.github.openminigameserver.worldedit.event.WorldEditBlockChange
import io.github.openminigameserver.worldedit.event.WorldEditBlockChangesEvent
import io.github.openminigameserver.worldedit.platform.MinestomBlockHandlers
import io.github.openminigameserver.worldedit.platform.MinestomBlockRegistry
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.CoordConversion
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.batch.AbsoluteBlockBatch
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler
import org.enginehub.linbus.tree.LinCompoundTag
import org.enginehub.linbus.tree.LinTagType
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture

class MinestomWorldNativeAccess(
    private val worldRef: WeakReference<Instance>,
    val useBlockBatch: Boolean,
) : WorldNativeAccess<Chunk, Block, Pos> {
    var actor: Actor? = null

    // One batch per chunk, applied one after another in flush(). Minestom applies the
    // chunks of a single AbsoluteBlockBatch in parallel, each under its chunk's write
    // lock, and block handlers such as concrete powder read neighbouring chunks from
    // onPlace. Two edge chunks applied at once then wait on each other forever and the
    // tick thread hangs on the first one it reads.
    private val pendingBatches = LinkedHashMap<Long, AbsoluteBlockBatch>()
    private var pendingBlockCount = 0
    private val pendingChanges = LinkedHashMap<BlockVec, WorldEditBlockChange>()
    private var applyingFullBlock = false
    private var fullBlockNbt: CompoundBinaryTag? = null
    private var fullBlockHandler: BlockHandler? = null

    private fun getWorld(): Instance = worldRef.get() ?: throw RuntimeException("World is unloaded")

    fun <B : BlockStateHolder<B>?> setFullBlock(
        position: BlockVector3?,
        block: B,
        sideEffects: SideEffectSet?,
    ): Boolean {
        val previousApplyingFullBlock = applyingFullBlock
        val previousBlockNbt = fullBlockNbt
        val previousBlockHandler = fullBlockHandler
        val blockNbt = (block as? BaseBlock)?.nbt
        applyingFullBlock = true
        fullBlockNbt = blockNbt?.let(::toNativeBlockNbt)
        fullBlockHandler = blockNbt?.let(::toNativeBlockHandler)
        return try {
            setBlock(position, block, sideEffects)
        } finally {
            applyingFullBlock = previousApplyingFullBlock
            fullBlockNbt = previousBlockNbt
            fullBlockHandler = previousBlockHandler
        }
    }

    override fun getChunk(
        x: Int,
        z: Int,
    ): Chunk = getWorld().getChunk(x, z) ?: throw RuntimeException("Chunk $x,$z is not loaded")

    override fun toNative(state: BlockState): Block {
        // BlockStateIdAccess is WorldEdit's id table. It is not guaranteed to be
        // Minestom's state id (in particular for states read from a schematic),
        // so always ask the platform registry first.
        val platformStateId = MinestomBlockRegistry.getInternalBlockStateId(state)
        val stateId =
            if (platformStateId.isPresent) {
                platformStateId.asInt
            } else {
                BlockStateIdAccess.getBlockStateId(state)
            }
        val block = Block.fromStateId(stateId) ?: Block.AIR
        val handler = MinestomBlockHandlers.resolve(block, if (applyingFullBlock) fullBlockHandler else null)
        if (!applyingFullBlock) return block.withHandler(handler)
        return block.withHandler(handler).withNbt(fullBlockNbt)
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
        if (useBlockBatch) {
            pendingBatches
                .getOrPut(CoordConversion.chunkIndex(x shr 4, z shr 4)) { AbsoluteBlockBatch() }
                .setBlock(x, y, z, state)
            recordPendingChange(x, y, z, oldState, state)
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
        if (applyingFullBlock) return true

        val world = getWorld()
        val block = world.getBlock(position)
        val handler = MinestomBlockHandlers.resolve(block, toNativeBlockHandler(tag))
        world.setBlock(position, block.withHandler(handler).withNbt(toNativeBlockNbt(tag)))
        return true
    }

    private fun toNativeBlockHandler(tag: LinCompoundTag): BlockHandler? =
        tag
            .findTag("id", LinTagType.stringTag())
            ?.value()
            ?.let { MinecraftServer.getBlockManager().getHandler(it) }

    private fun toNativeBlockNbt(tag: LinCompoundTag): CompoundBinaryTag? {
        val nbt =
            CompoundBinaryTag
                .builder()
                .put(MinestomAdapter.asNBT(tag))
                .remove("id")
                .remove("keepPacked")
                .remove("x")
                .remove("y")
                .remove("z")
                .build()
        return nbt.takeUnless(CompoundBinaryTag::isEmpty)
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
        val batches = pendingBatches.values.toList()
        pendingBatches.clear()
        val changes = pendingChanges.values.toList()
        val currentActor = actor
        pendingChanges.clear()
        pendingBlockCount = 0

        if (!useBlockBatch || batches.isEmpty()) {
            return CompletableFuture.completedFuture(Unit)
        }

        val completion = CompletableFuture<Unit>()
        val world = getWorld()
        applyInOrder(world, batches.iterator()) {
            try {
                dispatchChanges(currentActor, world, changes)
                completion.complete(Unit)
            } catch (throwable: Throwable) {
                completion.completeExceptionally(throwable)
            }
        }
        return completion
    }

    // Each callback runs after the previous chunk released its write lock, so only one
    // batch chunk is ever locked at a time. unsafeApply hands the next chunk to Minestom's
    // batch pool, so the stack does not grow with the chunk count.
    private fun applyInOrder(
        world: Instance,
        batches: Iterator<AbsoluteBlockBatch>,
        onDone: () -> Unit,
    ) {
        if (!batches.hasNext()) return onDone()
        batches.next().unsafeApply(world) { applyInOrder(world, batches, onDone) }
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
    ): Boolean =
        first.stateId() == second.stateId() &&
            first.nbt() == second.nbt() &&
            first.handler()?.key == second.handler()?.key

    private companion object {
        const val MAX_BLOCKS_PER_BATCH = 32_768
    }
}
