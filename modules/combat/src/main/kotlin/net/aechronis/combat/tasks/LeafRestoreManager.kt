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

private const val RESTORE_DELAY_MILLIS = 10_000L

private data class LeafPosition(
    val instanceId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
)

private data class PendingLeafRestore(
    val position: LeafPosition,
    val blockState: String,
)

object LeafRestoreManager {
    internal var restoreDelayMillis = RESTORE_DELAY_MILLIS

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
    private val pending = ConcurrentHashMap<LeafPosition, PendingLeafRestore>()
    private val scheduled = ConcurrentHashMap<LeafPosition, ScheduledFuture<*>>()
    private val executor =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "combat-leaf-restore").apply { isDaemon = true }
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

    fun temporarilyBreak(
        instance: Instance,
        position: BlockVec,
        block: Block,
    ): Boolean {
        if (!isLeaf(block)) return false

        val key = LeafPosition(instance.uuid, position.blockX, position.blockY, position.blockZ)
        val restore =
            PendingLeafRestore(
                position = key,
                blockState = block.state(),
            )

        synchronized(this) {
            if (pending.containsKey(key)) return false
            pending[key] = restore
        }

        instance.setBlock(position.blockX, position.blockY, position.blockZ, Block.AIR)
        schedule(instance, restore, restoreDelayMillis)
        return true
    }

    internal fun isLeaf(block: Block): Boolean = leaves.any { it.key() == block.key() }

    private fun schedule(
        instance: Instance,
        restore: PendingLeafRestore,
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

    private fun restore(
        instance: Instance,
        restore: PendingLeafRestore,
    ) {
        val target = runCatching { Block.fromState(restore.blockState) }.getOrNull()
        if (target == null) {
            finish(restore.position)
            return
        }

        val position = restore.position
        if (instance.getBlock(position.x, position.y, position.z).isAir) {
            instance.setBlock(position.x, position.y, position.z, target)
        }
        finish(position)
    }

    private fun restoreAllImmediately() {
        val instances = MinecraftServer.getInstanceManager().instances.toList()
        val instanceById = instances.associateBy { it.uuid }
        val fallback = instances.singleOrNull()

        for (restore in pending.values.toList()) {
            val instance = instanceById[restore.position.instanceId] ?: fallback ?: continue
            val position = restore.position
            val target = runCatching { Block.fromState(restore.blockState) }.getOrNull() ?: continue
            if (instance.getBlock(position.x, position.y, position.z).isAir) {
                instance.setBlock(position.x, position.y, position.z, target)
            }
            finish(position)
        }

        executor.shutdownNow()
    }

    @Synchronized
    private fun finish(position: LeafPosition) {
        pending.remove(position)
        scheduled.remove(position)?.cancel(false)
    }
}
