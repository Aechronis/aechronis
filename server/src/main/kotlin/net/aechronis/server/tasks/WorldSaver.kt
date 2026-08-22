package net.aechronis.server.tasks

import net.aechronis.nodes.Nodes
import net.aechronis.server.Server
import net.aechronis.vanilla.Vanilla
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.CompletableFuture

object WorldSaver {
    private const val SAVE_INTERVAL_MINUTES = 5L
    private const val CHUNK_SAVE_BATCH_SIZE = 512
    private const val PROGRESS_LOG_BATCHES = 16

    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                runCatching(::saveCheckpoint).onFailure { error ->
                    System.err.println("Failed to save server checkpoint: ${error.message}")
                    error.printStackTrace()
                }
            }.repeat(TaskSchedule.minutes(SAVE_INTERVAL_MINUTES))
            .schedule()
    }

    // runs on the tick thread so all persistence domains observe the same quiescent state
    internal fun saveCheckpoint() {
        Vanilla.saveCheckpoint()
        Nodes.saveWorld(checkIfNeedsSave = true, async = false)
        saveWorld().join()
    }

    fun saveWorld(): CompletableFuture<Void> {
        val chunks = Server.instance.chunks.toList()
        val startedAt = System.nanoTime()
        println("[WorldSave] Saving ${chunks.size} loaded chunks in batches of $CHUNK_SAVE_BATCH_SIZE...")

        return saveInBatches(
            items = chunks,
            batchSize = CHUNK_SAVE_BATCH_SIZE,
            saveItem = Server.instance::saveChunkToStorage,
            onProgress = { saved, total ->
                if (saved == total || saved % (CHUNK_SAVE_BATCH_SIZE * PROGRESS_LOG_BATCHES) == 0) {
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
