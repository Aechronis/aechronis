package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.aechronis.logger.utils.LogMetadata
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.CoordConversion
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.instance.InstanceSectionInvalidateEvent
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.BlockEntityDataPacket
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket
import net.minestom.server.utils.block.BlockUtils
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

private data class PreparedBlockMutation(
    val change: RollbackChange,
    val expected: Block?,
    val target: Block?,
)

private class StorageWorldChangeFailure(
    cause: Throwable,
) : IllegalStateException("storage change failed", cause)

/** Applies ordered world mutations, preserving storage interleaving and chunk packet batching. */
internal class RollbackWorldChanges(
    private val executor: ExecutorService,
    private val lifecycle: RollbackLifecycle,
    private val stateWriter: RollbackExternalChanges,
) {
    fun applyWorldChanges(
        instance: Instance,
        changes: List<RollbackChange>,
        reverse: Boolean,
        tolerateUnlinkedBlockConflicts: Boolean,
        applied: MutableList<RollbackChange>,
    ): CompletableFuture<Int> {
        val result = CompletableFuture<Int>()
        val blockPositions =
            changes
                .filter { it.changeKind == RollbackChangeKind.BLOCK }
                .mapNotNull { change ->
                    val x = change.x ?: return@mapNotNull null
                    val y = change.y ?: return@mapNotNull null
                    val z = change.z ?: return@mapNotNull null
                    RollbackBlockPosition(x, y, z)
                }.toSet()
        val storagePositions =
            changes
                .filter {
                    it.changeKind == RollbackChangeKind.STORAGE &&
                        it.storageSource.equals(LogMetadata.VANILLA, ignoreCase = true)
                }.mapNotNull { change ->
                    val location = change.storageId?.let(VanillaStorage::parseStorageId) ?: return@mapNotNull null
                    if (location.first != instance.uuid) return@mapNotNull null
                    RollbackBlockPosition(location.second, location.third, location.fourth)
                }.toSet()
        val linkedPositions = blockPositions intersect storagePositions
        val blockedPositions = mutableSetOf<RollbackBlockPosition>()
        var index = 0
        var skipped = 0
        lateinit var advance: () -> Unit

        fun scheduleBlockBatch() {
            ModuleScheduler.scheduleNextTick {
                if (lifecycle.isClosing || result.isDone) {
                    result.completeExceptionally(IllegalStateException("rollback service is closing"))
                    return@scheduleNextTick
                }
                try {
                    var visited = 0
                    while (
                        index < changes.size &&
                        changes[index].changeKind == RollbackChangeKind.BLOCK &&
                        visited < Logger.config.rollbackBatchSize.coerceAtLeast(1)
                    ) {
                        val change = changes[index++]
                        visited++
                        val x = change.x ?: error("block x is missing")
                        val y = change.y ?: error("block y is missing")
                        val z = change.z ?: error("block z is missing")
                        val position = RollbackBlockPosition(x, y, z)
                        if (position in blockedPositions) {
                            skipped++
                            continue
                        }
                        val expectedState = if (reverse) change.afterBlockState else change.beforeBlockState
                        val expectedNbt = if (reverse) change.afterBlockNbt else change.beforeBlockNbt
                        val expectedHandler = if (reverse) change.afterBlockHandler else change.beforeBlockHandler
                        val targetState = if (reverse) change.beforeBlockState else change.afterBlockState
                        val targetNbt = if (reverse) change.beforeBlockNbt else change.afterBlockNbt
                        val targetHandler = if (reverse) change.beforeBlockHandler else change.afterBlockHandler
                        val expected = RollbackBlocks.decode(expectedState, null, expectedNbt, expectedHandler)
                        val target = RollbackBlocks.decode(targetState, null, targetNbt, targetHandler)
                        val matches =
                            expected != null &&
                                target != null &&
                                RollbackBlocks.matches(instance.getBlock(x, y, z), expected, expectedHandler)
                        if (!matches) {
                            if (!tolerateUnlinkedBlockConflicts || position in linkedPositions) {
                                error("block at $x,$y,$z changed after preview")
                            }
                            blockedPositions += position
                            skipped++
                            continue
                        }
                        if (!VanillaStorage.applyBlockTransition(instance, x, y, z, target)) {
                            if (!tolerateUnlinkedBlockConflicts || position in linkedPositions) {
                                error("block transition at $x,$y,$z was rejected")
                            }
                            blockedPositions += position
                            skipped++
                            continue
                        }
                        applied += change
                    }
                    if (index == changes.size) {
                        result.complete(skipped)
                    } else if (changes[index].changeKind == RollbackChangeKind.BLOCK) {
                        scheduleBlockBatch()
                    } else {
                        advance()
                    }
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        }

        advance = {
            if (!result.isDone) {
                when {
                    lifecycle.isClosing -> {
                        result.completeExceptionally(IllegalStateException("rollback service is closing"))
                    }

                    index == changes.size -> {
                        result.complete(skipped)
                    }

                    changes[index].changeKind == RollbackChangeKind.BLOCK -> {
                        scheduleBlockBatch()
                    }

                    changes[index].changeKind == RollbackChangeKind.STORAGE -> {
                        val change = changes[index]
                        stateWriter.applyStorage(listOf(change), reverse, applied).whenComplete { _, failure ->
                            if (failure != null) {
                                result.completeExceptionally(StorageWorldChangeFailure(failure))
                            } else {
                                index++
                                ModuleScheduler.scheduleNextTick { advance() }
                            }
                        }
                    }

                    else -> {
                        result.completeExceptionally(
                            IllegalArgumentException("non-world change in ordered world sequence"),
                        )
                    }
                }
            }
        }
        advance()
        return result
    }

    fun applyChunkRestoreWorldChanges(
        instance: Instance,
        changes: List<RollbackChange>,
        reverse: Boolean,
        tolerateConflicts: Boolean,
        applied: MutableList<RollbackChange>,
    ): CompletableFuture<Int> {
        val result = CompletableFuture<Int>()
        lifecycle
            .trackedStage(start = {
                CompletableFuture.supplyAsync({
                    changes.map { change ->
                        val expectedState = if (reverse) change.afterBlockState else change.beforeBlockState
                        val expectedNbt = if (reverse) change.afterBlockNbt else change.beforeBlockNbt
                        val expectedHandler = if (reverse) change.afterBlockHandler else change.beforeBlockHandler
                        val targetState = if (reverse) change.beforeBlockState else change.afterBlockState
                        val targetNbt = if (reverse) change.beforeBlockNbt else change.afterBlockNbt
                        val targetHandler = if (reverse) change.beforeBlockHandler else change.afterBlockHandler
                        PreparedBlockMutation(
                            change = change,
                            expected = RollbackBlocks.decode(expectedState, null, expectedNbt, expectedHandler),
                            target = RollbackBlocks.decode(targetState, null, targetNbt, targetHandler),
                        )
                    }
                }, executor)
            })
            .whenComplete { mutations, preparationFailure ->
                if (preparationFailure != null) {
                    result.completeExceptionally(preparationFailure)
                    return@whenComplete
                }

                var index = 0
                var skipped = 0
                lateinit var scheduleNextBatch: () -> Unit

                fun conflict(message: String) {
                    if (!tolerateConflicts) error(message)
                    skipped++
                    index++
                }

                fun applyManagedTransition(mutation: PreparedBlockMutation) {
                    val change = mutation.change
                    val x = change.x ?: error("block x is missing")
                    val y = change.y ?: error("block y is missing")
                    val z = change.z ?: error("block z is missing")
                    val expected = mutation.expected
                    val target = mutation.target
                    val matches =
                        expected != null &&
                            target != null &&
                            RollbackBlocks.matches(instance.getBlock(x, y, z), expected, expected.handler()?.key?.asString())
                    if (!matches) {
                        conflict("block at $x,$y,$z changed after preview")
                        return
                    }
                    if (!VanillaStorage.applyBlockTransition(instance, x, y, z, target)) {
                        conflict("block transition at $x,$y,$z was rejected")
                        return
                    }
                    applied += change
                    index++
                }

                fun applyChunkSegment(
                    chunk: Chunk,
                    tickStartedAt: Long,
                    tickBudgetNanos: Long,
                ): Int {
                    val sectionUpdates = linkedMapOf<Int, MutableList<Long>>()
                    val blockEntityUpdates = mutableListOf<BlockEntityDataPacket>()
                    val invalidatedSections = linkedSetOf<Int>()
                    var visited = 0
                    chunk.lockWriteLock()
                    try {
                        while (index < mutations.size) {
                            if (visited > 0 && System.nanoTime() - tickStartedAt >= tickBudgetNanos) break
                            val mutation = mutations[index]
                            val change = mutation.change
                            val x = change.x ?: error("block x is missing")
                            val y = change.y ?: error("block y is missing")
                            val z = change.z ?: error("block z is missing")
                            if ((x shr 4) != chunk.chunkX || (z shr 4) != chunk.chunkZ) break
                            val expected = mutation.expected
                            val target = mutation.target
                            val current = chunk.getBlock(x, y, z)
                            if (current.compare(Block.BARREL) || target?.compare(Block.BARREL) == true) break

                            visited++
                            val matches =
                                expected != null &&
                                    target != null &&
                                    RollbackBlocks.matches(current, expected, expected.handler()?.key?.asString())
                            if (!matches) {
                                conflict("block at $x,$y,$z changed after preview")
                                continue
                            }

                            chunk.setBlock(x, y, z, target)
                            applied += change
                            index++
                            val sectionY = y shr 4
                            invalidatedSections += sectionY
                            sectionUpdates
                                .getOrPut(sectionY, ::mutableListOf)
                                .add(
                                    CoordConversion.encodeSectionBlockChange(
                                        CoordConversion.globalToSectionRelative(x),
                                        CoordConversion.globalToSectionRelative(y),
                                        CoordConversion.globalToSectionRelative(z),
                                        target.stateId().toLong(),
                                    ),
                                )
                            target.registry()?.blockEntityType()?.let { type ->
                                blockEntityUpdates +=
                                    BlockEntityDataPacket(
                                        BlockVec(x, y, z),
                                        type,
                                        BlockUtils.extractClientNbt(target),
                                    )
                            }
                        }
                    } finally {
                        chunk.unlockWriteLock()
                    }

                    if (sectionUpdates.isNotEmpty()) {
                        (instance as? InstanceContainer)?.refreshLastBlockChangeTime()
                        sectionUpdates.forEach { (sectionY, updates) ->
                            chunk.sendPacketToViewers(
                                MultiBlockChangePacket(chunk.chunkX, sectionY, chunk.chunkZ, updates.toLongArray()),
                            )
                        }
                        blockEntityUpdates.forEach(chunk::sendPacketToViewers)
                        invalidatedSections.forEach { sectionY ->
                            EventDispatcher.call(
                                InstanceSectionInvalidateEvent(instance, chunk.chunkX, sectionY, chunk.chunkZ),
                            )
                        }
                    }
                    return visited
                }

                scheduleNextBatch = {
                    ModuleScheduler.scheduleNextTick {
                        if (result.isDone) return@scheduleNextTick
                        if (lifecycle.isClosing) {
                            result.completeExceptionally(IllegalStateException("rollback service is closing"))
                            return@scheduleNextTick
                        }
                        try {
                            val tickStartedAt = System.nanoTime()
                            val tickBudgetNanos =
                                TimeUnit.MILLISECONDS.toNanos(Logger.config.chunkRestoreTickBudgetMillis.coerceAtLeast(1))
                            var visited = 0
                            while (index < mutations.size) {
                                if (visited > 0 && System.nanoTime() - tickStartedAt >= tickBudgetNanos) break
                                val mutation = mutations[index]
                                val change = mutation.change
                                val x = change.x ?: error("block x is missing")
                                val y = change.y ?: error("block y is missing")
                                val z = change.z ?: error("block z is missing")
                                val current = instance.getBlock(x, y, z)
                                if (current.compare(Block.BARREL) || mutation.target?.compare(Block.BARREL) == true) {
                                    applyManagedTransition(mutation)
                                    visited++
                                    continue
                                }
                                val chunk =
                                    instance.getChunk(x shr 4, z shr 4)
                                        ?: error("chunk ${x shr 4},${z shr 4} unloaded during chunk restore")
                                check(!chunk.isReadOnly) { "chunk ${chunk.chunkX},${chunk.chunkZ} is read-only" }
                                val segmentVisited = applyChunkSegment(chunk, tickStartedAt, tickBudgetNanos)
                                if (segmentVisited == 0) {
                                    applyManagedTransition(mutation)
                                    visited++
                                } else {
                                    visited += segmentVisited
                                }
                            }
                            if (index == mutations.size) {
                                result.complete(skipped)
                            } else {
                                scheduleNextBatch()
                            }
                        } catch (failure: Throwable) {
                            result.completeExceptionally(failure)
                        }
                    }
                }
                scheduleNextBatch()
            }
        return result
    }

    fun isStorageWorldChangeFailure(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            if (current is StorageWorldChangeFailure) return true
            current = current.cause
        }
        return false
    }

    fun loadChunks(
        instance: Instance,
        changes: List<BlockChangePlan>,
    ): CompletableFuture<Void> {
        val chunks = changes.map { (it.x shr 4) to (it.z shr 4) }.toSet()
        return CompletableFuture.allOf(*chunks.map { (x, z) -> instance.loadChunk(x, z) }.toTypedArray())
    }

    fun loadChunksFromChanges(
        instance: Instance,
        changes: List<RollbackChange>,
    ): CompletableFuture<Void> {
        val chunks = changes.mapNotNull { change -> change.x?.let { x -> change.z?.let { z -> (x shr 4) to (z shr 4) } } }.toSet()
        return CompletableFuture.allOf(*chunks.map { (x, z) -> instance.loadChunk(x, z) }.toTypedArray())
    }
}
