package net.aechronis.combat.tasks

import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val BLOCK_RESTORE_DELAY_MILLIS = 10 * 60 * 1_000L

private data class BlockPosition(
    val instanceId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
)

private data class PendingBlockRestore(
    val position: BlockPosition,
    val originalBlock: Block,
    val temporaryBlock: Block,
)

object BlockRestoreManager {
    internal var restoreDelayMillis = BLOCK_RESTORE_DELAY_MILLIS

    private val leaves =
        setOf(
            Block.OAK_LEAVES,
            Block.SPRUCE_LEAVES,
            Block.BIRCH_LEAVES,
            Block.JUNGLE_LEAVES,
            Block.ACACIA_LEAVES,
            Block.DARK_OAK_LEAVES,
            Block.CHERRY_LEAVES,
            Block.MANGROVE_LEAVES,
            Block.PALE_OAK_LEAVES,
            Block.AZALEA_LEAVES,
            Block.FLOWERING_AZALEA_LEAVES,
        )
    private val pending = ConcurrentHashMap<BlockPosition, PendingBlockRestore>()
    private val scheduled = ConcurrentHashMap<BlockPosition, Task>()
    private var initialized = false

    @Synchronized
    fun initialize() {
        if (initialized) return
        initialized = true
    }

    @Synchronized
    fun shutdown() {
        if (!initialized) return
        scheduled.values.forEach(Task::cancel)
        scheduled.clear()
        // Restores remove themselves from pending as they complete (see restore()'s handling of
        // neighbour-update failures). A genuinely unexpected failure here still retains the
        // remaining entries and initialized state so coordinated teardown can retry.
        restoreAllImmediately()
        pending.clear()
        initialized = false
    }

    fun temporarilyBreakLeaf(
        instance: Instance,
        position: BlockVec,
        block: Block,
    ): Boolean {
        if (!isLeaf(block)) return false
        return temporarilyReplace(instance, position, block, Block.AIR)
    }

    @Synchronized
    fun temporarilyReplace(
        instance: Instance,
        position: BlockVec,
        originalBlock: Block,
        temporaryBlock: Block,
    ): Boolean {
        check(initialized) { "BlockRestoreManager is not initialized" }
        val key = BlockPosition(instance.uuid, position.blockX, position.blockY, position.blockZ)
        val existing = pending[key]
        val current = instance.getBlock(position)
        val original =
            if (existing != null && sameBlock(current, existing.temporaryBlock)) {
                existing.originalBlock
            } else {
                if (existing != null) finish(key)
                originalBlock
            }
        val restore = PendingBlockRestore(key, original, temporaryBlock)

        pending[key] = restore
        instance.setBlock(position, temporaryBlock)
        schedule(instance, restore, restoreDelayMillis)
        return true
    }

    internal fun isLeaf(block: Block): Boolean = leaves.any { it.key() == block.key() }

    private fun schedule(
        instance: Instance,
        restore: PendingBlockRestore,
        delayMillis: Long,
    ) {
        scheduled[restore.position]?.cancel()
        scheduled[restore.position] =
            ModuleScheduler
                .buildTask { restore(instance, restore) }
                .delay(TaskSchedule.millis(delayMillis))
                .schedule()
    }

    @Synchronized
    private fun restore(
        instance: Instance,
        restore: PendingBlockRestore,
    ) {
        if (pending[restore.position] !== restore) return

        val position = restore.position
        val current = instance.getBlock(position.x, position.y, position.z)
        if (sameBlock(current, restore.temporaryBlock)) {
            // Minestom commits the block to its chunk before running neighbour placement-rule
            // cascades, so a throw here (e.g. a pre-existing neighbour whose block state a
            // placement-rule library can't parse) happens after our write already landed. Log it
            // rather than letting it bubble up: propagating would leave this entry pending
            // forever and fail module quiescing on every retry, since the corrupt neighbour never
            // resolves itself.
            runCatching { instance.setBlock(position.x, position.y, position.z, restore.originalBlock) }
                .onFailure { error ->
                    System.err.println(
                        "[Combat] Block restore neighbour update failed at " +
                            "${position.instanceId}/${position.x},${position.y},${position.z}: ${error.message}",
                    )
                }
        }
        finish(position)
    }

    private fun restoreAllImmediately() {
        val instances = MinecraftServer.getInstanceManager().instances.toList()
        val instanceById = instances.associateBy { it.uuid }
        val fallback = instances.singleOrNull()
        var failure: Throwable? = null

        for (restore in pending.values.toList()) {
            val instance = instanceById[restore.position.instanceId] ?: fallback ?: continue
            runCatching { restore(instance, restore) }.onFailure { error ->
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    @Synchronized
    private fun finish(position: BlockPosition) {
        pending.remove(position)
        scheduled.remove(position)?.cancel()
    }

    private fun sameBlock(
        first: Block,
        second: Block,
    ): Boolean =
        first.state() == second.state() &&
            first.nbt() == second.nbt() &&
            first.handler()?.key == second.handler()?.key
}
