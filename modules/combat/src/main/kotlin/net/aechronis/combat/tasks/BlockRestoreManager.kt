package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Vec
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val BLOCK_RESTORE_DELAY_MILLIS = 10 * 60 * 1_000L
private const val RETRY_MILLIS = 5_000L

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
        Combat.lowPriorityEventNode.addListener(PlayerBlockPlaceEvent::class.java, ::onPlace)
    }

    @Synchronized
    fun shutdown() {
        if (!initialized) return
        flush()
        initialized = false
    }

    /** Settle temporary changes before another module tears down structures, while staying active. */
    @Synchronized
    fun flush() {
        if (!initialized) return
        scheduled.values.forEach(Task::cancel)
        scheduled.clear()
        // Restores remove themselves from pending as they complete (see restore()'s handling of
        // neighbour-update failures). A genuinely unexpected failure here still retains the
        // remaining entries and initialized state so coordinated teardown can retry.
        restoreAllImmediately()
        check(pending.isEmpty()) { "Failed to restore ${pending.size} temporary block(s) before flushing" }
        ModuleScheduler.releaseCancelledTasks()
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
        try {
            // Temporary combat edits must not cascade into untracked neighbour changes or
            // invoke placement rules that can fail partway through an explosion.
            instance.setBlock(position, temporaryBlock, false)
        } finally {
            // A block handler can throw after the write has landed. Keep its recovery queued.
            schedule(instance, restore, restoreDelayMillis)
        }
        return true
    }

    internal fun isLeaf(block: Block): Boolean = leaves.any { it.key() == block.key() }

    @Synchronized
    private fun onPlace(event: PlayerBlockPlaceEvent) {
        if (!initialized || event.isCancelled) return
        val instance = event.player.instance
        val position = event.blockPosition
        val key = BlockPosition(instance.uuid, position.blockX, position.blockY, position.blockZ)
        val pendingRestore = pending[key] ?: return
        if (!sameBlock(instance.getBlock(position), pendingRestore.temporaryBlock)) {
            finish(key)
            return
        }

        // Repair the saved block directly, preserving its state and data and leaving the
        // held item untouched. Reuse restoration's retry handling.
        event.isCancelled = true
        restore(instance, pendingRestore)
    }

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
        // Unloaded chunk (auto-load off) throws; nothing was written, so keep the restore and retry.
        val current = runCatching { instance.getBlock(position.x, position.y, position.z) }.getOrNull()
        if (current == null) {
            schedule(instance, restore, RETRY_MILLIS)
            return
        }
        if (sameBlock(current, restore.temporaryBlock)) {
            // Restore the saved state without neighbouring placement-rule cascades, just like
            // the temporary edit. A block handler may still throw after committing the write.
            runCatching { instance.setBlock(position.x, position.y, position.z, restore.originalBlock, false) }
                .onFailure { error ->
                    System.err.println(
                        "[Combat] Block restore failed at " +
                            "${position.instanceId}/${position.x},${position.y},${position.z}: ${error.message}",
                    )
                }
            // A throwing handler can skip Minestom's BlockChangePacket. If the write landed,
            // resend it; if it did not, try again later.
            val now = runCatching { instance.getBlock(position.x, position.y, position.z) }.getOrNull()
            if (now == null || !sameBlock(now, restore.originalBlock)) {
                schedule(instance, restore, RETRY_MILLIS)
                return
            }
            val packet = BlockChangePacket(Vec(position.x.toDouble(), position.y.toDouble(), position.z.toDouble()), now)
            instance.getChunkAt(position.x.toDouble(), position.z.toDouble())?.viewers?.forEach { it.sendPacket(packet) }
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
            runCatching {
                // Teardown cannot defer unloaded chunks to a task in the departing module.
                val position = restore.position
                instance.loadChunk(position.x shr 4, position.z shr 4).join()
                restore(instance, restore)
            }.onFailure { error ->
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
