package net.aechronis.combat.tasks

import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
    private val scheduled = ConcurrentHashMap<BlockPosition, ScheduledFuture<*>>()
    private val executor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "combat-block-restore").apply { isDaemon = true }
        }

    private var initialized = false

    @Synchronized
    fun initialize() {
        if (initialized) return
        initialized = true

        MinecraftServer
            .getSchedulerManager()
            .buildShutdownTask { restoreAllImmediately() }
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
        scheduled[restore.position]?.cancel(false)
        scheduled[restore.position] =
            executor.schedule(
                { instance.scheduleNextTick { restore(instance, restore) } },
                delayMillis,
                TimeUnit.MILLISECONDS,
            )
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
            instance.setBlock(position.x, position.y, position.z, restore.originalBlock)
        }
        finish(position)
    }

    private fun restoreAllImmediately() {
        val instances = MinecraftServer.getInstanceManager().instances.toList()
        val instanceById = instances.associateBy { it.uuid }
        val fallback = instances.singleOrNull()

        for (restore in pending.values.toList()) {
            val instance = instanceById[restore.position.instanceId] ?: fallback ?: continue
            restore(instance, restore)
        }

        executor.shutdownNow()
    }

    @Synchronized
    private fun finish(position: BlockPosition) {
        pending.remove(position)
        scheduled.remove(position)?.cancel(false)
    }

    private fun sameBlock(
        first: Block,
        second: Block,
    ): Boolean =
        first.state() == second.state() &&
            first.nbt() == second.nbt() &&
            first.handler()?.key == second.handler()?.key
}
