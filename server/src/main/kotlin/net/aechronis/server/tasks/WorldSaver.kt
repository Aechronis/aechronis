package net.aechronis.server.tasks

import net.aechronis.nodes.Nodes
import net.aechronis.server.Server
import net.aechronis.vanilla.Vanilla
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.Chunk
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.CompletableFuture

object WorldSaver {
    private const val SAVE_INTERVAL_MINUTES = 5L
    private const val CHUNK_SAVE_BATCH_SIZE = 8
    private const val PROGRESS_LOG_CHUNKS = 8_192
    private val checkpointLock = Any()
    private val worldSaveQueue = SerialFutureQueue()
    private var activeCheckpoint = CompletableFuture.completedFuture<Void>(null)

    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                runCatching(::startCheckpoint).onFailure { error ->
                    System.err.println("Failed to save server checkpoint: ${error.message}")
                    error.printStackTrace()
                }
            }.repeat(TaskSchedule.minutes(SAVE_INTERVAL_MINUTES))
            .schedule()
    }

    private fun startCheckpoint() {
        val checkpoint =
            synchronized(checkpointLock) {
                if (!activeCheckpoint.isDone) {
                    println("[WorldSave] Previous checkpoint is still running; skipping this interval.")
                    return
                }
                saveCheckpoint().also { activeCheckpoint = it }
            }

        checkpoint.whenComplete { _, error ->
            if (error != null) {
                System.err.println("Failed to save server checkpoint: ${error.message}")
                error.printStackTrace()
            }
        }
    }

    // snapshot preparation runs on the tick thread; bulk persistence completes in the background
    internal fun saveCheckpoint(): CompletableFuture<Void> =
        saveCheckpointAsync(
            prepare = Vanilla::saveCheckpoint,
            saveState = { Nodes.saveWorld(checkIfNeedsSave = true, async = true) },
            saveChunks = ::saveWorld,
        )

    fun saveWorld(): CompletableFuture<Void> {
        val chunks = Server.instance.chunks.toList()
        return worldSaveQueue.submit { saveChunks(chunks) }
    }

    private fun saveChunks(chunks: List<Chunk>): CompletableFuture<Void> {
        val startedAt = System.nanoTime()
        println("[WorldSave] Saving ${chunks.size} loaded chunks in batches of $CHUNK_SAVE_BATCH_SIZE...")

        return saveInBatches(
            items = chunks,
            batchSize = CHUNK_SAVE_BATCH_SIZE,
            saveItem = Server.instance::saveChunkToStorage,
            onProgress = { saved, total ->
                if (saved == total || saved % PROGRESS_LOG_CHUNKS == 0) {
                    println("[WorldSave] Saved $saved/$total loaded chunks.")
                }
            },
        ).thenCompose {
            Server.instance.saveInstance()
        }.whenComplete { _, error ->
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            if (error == null) {
                println("[WorldSave] Completed ${chunks.size} loaded chunks in ${elapsedMillis}ms.")
            } else {
                System.err.println("[WorldSave] Failed after ${elapsedMillis}ms: ${error.message}")
            }
        }
    }
}

internal fun saveCheckpointAsync(
    prepare: () -> Unit,
    saveState: () -> CompletableFuture<Void>,
    saveChunks: () -> CompletableFuture<Void>,
): CompletableFuture<Void> {
    val preparation =
        try {
            prepare()
            CompletableFuture.completedFuture<Void>(null)
        } catch (error: Throwable) {
            CompletableFuture.failedFuture(error)
        }
    val stateSave = invokeSave(saveState)
    val chunkSave = invokeSave(saveChunks)
    return CompletableFuture.allOf(preparation, stateSave, chunkSave)
}

private fun invokeSave(save: () -> CompletableFuture<Void>): CompletableFuture<Void> =
    try {
        save()
    } catch (error: Throwable) {
        CompletableFuture.failedFuture(error)
    }

internal class SerialFutureQueue {
    private val lock = Any()
    private var tail = CompletableFuture.completedFuture<Void>(null)

    fun submit(save: () -> CompletableFuture<Void>): CompletableFuture<Void> =
        synchronized(lock) {
            tail
                .handle { _, _ -> null }
                .thenCompose {
                    try {
                        save()
                    } catch (error: Throwable) {
                        CompletableFuture.failedFuture(error)
                    }
                }.also { tail = it }
        }
}

internal fun <T> saveInBatches(
    items: List<T>,
    batchSize: Int,
    saveItem: (T) -> CompletableFuture<Void>,
    onProgress: (saved: Int, total: Int) -> Unit = { _, _ -> },
): CompletableFuture<Void> {
    require(batchSize > 0) { "Chunk save batch size must be positive" }

    var saved = 0
    return items.chunked(batchSize).fold(CompletableFuture.completedFuture(null)) { previous, batch ->
        previous
            .thenCompose {
                val saves =
                    batch.map { item ->
                        try {
                            saveItem(item)
                        } catch (error: Throwable) {
                            CompletableFuture.failedFuture(error)
                        }
                    }
                CompletableFuture.allOf(*saves.toTypedArray())
            }.thenRun {
                saved += batch.size
                onProgress(saved, items.size)
            }
    }
}
