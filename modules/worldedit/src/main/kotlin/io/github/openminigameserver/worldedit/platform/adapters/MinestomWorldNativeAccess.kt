package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.internal.block.BlockStateIdAccess
import com.sk89q.worldedit.internal.wna.WorldNativeAccess
import com.sk89q.worldedit.world.block.BlockState
import net.aechronis.logger.Logger
import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockLogEntry
import net.aechronis.logger.objects.LogMetadata
import net.kyori.adventure.nbt.BinaryTagIO
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.batch.AbsoluteBlockBatch
import net.minestom.server.instance.block.Block
import org.enginehub.linbus.tree.LinCompoundTag
import java.io.ByteArrayOutputStream
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
    private val pendingChanges = LinkedHashMap<BlockPosition, PendingChange>()

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
            world.setBlock(x, y, z, state)
            logChange(x, y, z, oldState, state)
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
        pendingChanges.clear()

        if (!useBlockBatch || batch == null || !hasPendingChanges) {
            hasPendingChanges = false
            pendingBlockCount = 0
            return CompletableFuture.completedFuture(Unit)
        }

        hasPendingChanges = false
        pendingBlockCount = 0
        val completion = CompletableFuture<Unit>()
        batch.unsafeApply(getWorld()) {
            changes.forEach { change ->
                logChange(change.x, change.y, change.z, change.oldState, change.newState)
            }
            completion.complete(Unit)
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
        val position = BlockPosition(x, y, z)
        val previous = pendingChanges[position]
        val originalState = previous?.oldState ?: oldState
        if (sameState(originalState, newState)) {
            pendingChanges.remove(position)
        } else {
            pendingChanges[position] = PendingChange(x, y, z, originalState, newState)
        }
    }

    private fun logChange(
        x: Int,
        y: Int,
        z: Int,
        oldState: Block,
        newState: Block,
    ) {
        val actor = actor ?: return
        if (sameState(oldState, newState)) return

        val entry =
            BlockLogEntry(
                timestamp = System.currentTimeMillis(),
                playerUuid = actor.uniqueId,
                playerName = actor.name,
                x = x,
                y = y,
                z = z,
                blockOld = oldState.key().asString(),
                blockNew = newState.key().asString(),
                action = if (newState.isAir) BlockAction.BREAK else BlockAction.PLACE,
                instanceUuid = getWorld().uuid,
                blockOldState = oldState.state(),
                blockNewState = newState.state(),
                blockOldNbt = oldState.nbtBytes(),
                blockNewNbt = newState.nbtBytes(),
                source = LogMetadata.WORLDEDIT,
                origin = LogMetadata.WORLDEDIT,
            )
        Logger.repository.insertAsync(entry)
    }

    private fun sameState(
        first: Block,
        second: Block,
    ): Boolean = first.stateId() == second.stateId() && first.nbt() == second.nbt()

    private fun Block.nbtBytes(): ByteArray? {
        if (!hasNbt()) return null
        val blockNbt = nbt() ?: return null
        return runCatching {
            ByteArrayOutputStream().use { output ->
                BinaryTagIO.writer().write(blockNbt, output)
                output.toByteArray()
            }
        }.getOrNull()
    }

    private data class BlockPosition(
        val x: Int,
        val y: Int,
        val z: Int,
    )

    private data class PendingChange(
        val x: Int,
        val y: Int,
        val z: Int,
        val oldState: Block,
        val newState: Block,
    )

    private companion object {
        const val MAX_BLOCKS_PER_BATCH = 32_768
    }
}
