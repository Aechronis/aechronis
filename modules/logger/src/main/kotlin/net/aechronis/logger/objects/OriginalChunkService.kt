package net.aechronis.logger.objects

import net.aechronis.logger.utils.ItemCodec
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import net.minestom.server.world.DimensionType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OriginalChunkService(
    originalWorldPath: Path,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val originalWorldPath = originalWorldPath.toAbsolutePath().normalize()

    fun computePlanAsync(
        instance: Instance,
        chunkX: Int,
        chunkZ: Int,
    ): CompletableFuture<RollbackPlan> = computePlanAsync(instance, chunkX, chunkZ, 0)

    fun computePlanAsync(
        instance: Instance,
        centerChunkX: Int,
        centerChunkZ: Int,
        radius: Int,
    ): CompletableFuture<RollbackPlan> {
        require(radius >= 0) { "chunk radius cannot be negative" }
        if (!Files.isDirectory(originalWorldPath)) {
            return CompletableFuture.failedFuture(
                IllegalStateException("original world is missing at $originalWorldPath"),
            )
        }

        return CompletableFuture.supplyAsync({
            val loader = AnvilLoader(originalWorldPath, DimensionType.OVERWORLD.key())
            val timestamp = System.currentTimeMillis()
            val changes = mutableListOf<BlockChangePlan>()
            for (chunkZ in centerChunkZ - radius..centerChunkZ + radius) {
                for (chunkX in centerChunkX - radius..centerChunkX + radius) {
                    val originalChunk =
                        loader.loadChunk(instance, chunkX, chunkZ)
                            ?: throw IllegalArgumentException("chunk $chunkX,$chunkZ is absent from $originalWorldPath")
                    try {
                        val liveChunk = instance.loadChunk(chunkX, chunkZ).join()
                        changes += compileChanges(liveChunk, originalChunk, timestamp)
                    } finally {
                        loader.unloadChunk(originalChunk)
                    }
                }
            }

            RollbackPlan(
                kind = RollbackOperationKind.CHUNK_RESTORE,
                instanceUuid = instance.uuid,
                targetTs = timestamp,
                queryDesc =
                    "original chunks within radius $radius of $centerChunkX,$centerChunkZ from $originalWorldPath",
                safeMode = true,
                blockChanges = changes,
                skippedBlockCount = 0,
            )
        }, executor)
    }

    private fun compileChanges(
        liveChunk: Chunk,
        originalChunk: Chunk,
        timestamp: Long,
    ): List<BlockChangePlan> {
        check(liveChunk.minSection == originalChunk.minSection && liveChunk.maxSection == originalChunk.maxSection) {
            "original chunk has incompatible world height"
        }

        val changes = mutableListOf<BlockChangePlan>()
        val startX = liveChunk.chunkX shl 4
        val startZ = liveChunk.chunkZ shl 4
        liveChunk.lockReadLock()
        originalChunk.lockReadLock()
        try {
            for (y in liveChunk.minSection * 16 until liveChunk.maxSection * 16) {
                for (localZ in 0 until 16) {
                    for (localX in 0 until 16) {
                        val x = startX + localX
                        val z = startZ + localZ
                        val current = liveChunk.getBlock(x, y, z)
                        val original = originalChunk.getBlock(x, y, z)
                        if (sameBlock(current, original)) continue
                        changes +=
                            BlockChangePlan(
                                blockLogId = null,
                                timestamp = timestamp,
                                x = x,
                                y = y,
                                z = z,
                                expectedState = current.state(),
                                expectedMaterialKey = current.key().asString(),
                                expectedNbt = ItemCodec.encodeBlockNbt(current.nbt()),
                                targetState = original.state(),
                                targetMaterialKey = original.key().asString(),
                                targetNbt = ItemCodec.encodeBlockNbt(original.nbt()),
                                expectedHandlerKey = current.handler()?.key?.asString(),
                                targetHandlerKey = original.handler()?.key?.asString(),
                            )
                    }
                }
            }
        } finally {
            originalChunk.unlockReadLock()
            liveChunk.unlockReadLock()
        }

        return changes
    }

    private fun sameBlock(
        first: Block,
        second: Block,
    ): Boolean =
        first.state() == second.state() &&
            first.nbt() == second.nbt() &&
            first.handler()?.key == second.handler()?.key

    override fun close() {
        executor.close()
    }
}
