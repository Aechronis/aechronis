package net.aechronis.server.tasks

import net.aechronis.server.Server
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.Chunk
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Owns durable world checkpoints independently of any reloadable module generation. */
object WorldSaver {
    private const val SAVE_INTERVAL_MINUTES = 5L
    private const val CHUNK_SAVE_BATCH_SIZE = 8
    private const val PROGRESS_LOG_CHUNKS = 8_192
    private val checkpointLock = Any()
    private val worldSaveQueue = SerialFutureQueue()
    private var activeCheckpoint = CompletableFuture.completedFuture<Void>(null)
    private var periodicTask: Task? = null
    private var stopping = false

    fun start(saveModules: () -> CompletableFuture<Void>) {
        synchronized(checkpointLock) {
            check(periodicTask == null) { "WorldSaver is already started" }
            stopping = false
            periodicTask =
                MinecraftServer
                    .getSchedulerManager()
                    .buildTask { startCheckpoint(saveModules) }
                    .repeat(TaskSchedule.minutes(SAVE_INTERVAL_MINUTES))
                    .schedule()
        }
    }

    private fun startCheckpoint(saveModules: () -> CompletableFuture<Void>) {
        val checkpoint =
            synchronized(checkpointLock) {
                if (stopping) return
                if (!activeCheckpoint.isDone) {
                    println("[WorldSave] Previous checkpoint is still running; skipping this interval.")
                    return
                }
                saveCheckpointAsync(
                    saveModules = saveModules,
                    saveChunks = ::saveWorld,
                ).also { activeCheckpoint = it }
            }

        checkpoint.whenComplete { _, error ->
            if (error != null) {
                System.err.println("Failed to save server checkpoint: ${error.message}")
                error.printStackTrace()
            }
        }
    }

    /** Waits only when module code may still be executing as part of an active checkpoint. */
    fun awaitCheckpoint() {
        val checkpoint = synchronized(checkpointLock) { activeCheckpoint }
        await(checkpoint, "active server checkpoint")
    }

    fun saveWorld(): CompletableFuture<Void> {
        val chunks = Server.instance.chunks.toList()
        return worldSaveQueue.submit { saveChunks(chunks) }
    }

    fun saveWorldAndWait() {
        await(saveWorld(), "core world save")
    }

    fun shutdown() {
        val task =
            synchronized(checkpointLock) {
                stopping = true
                periodicTask.also { periodicTask = null }
            }

        var failure: Throwable? = null
        task?.let { scheduled ->
            runCatching(scheduled::cancel).onFailure { error -> failure = error }
        }
        runCatching(::awaitCheckpoint).onFailure { error ->
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
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

    private fun await(
        future: CompletableFuture<Void>,
        description: String,
    ) {
        try {
            future.get(SAVE_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for $description", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException("$description did not finish within $SAVE_WAIT_TIMEOUT_SECONDS seconds", error)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private const val SAVE_WAIT_TIMEOUT_SECONDS = 300L
}

internal fun saveCheckpointAsync(
    saveModules: () -> CompletableFuture<Void>,
    saveChunks: () -> CompletableFuture<Void>,
): CompletableFuture<Void> = invokeSave(saveModules).thenCompose { invokeSave(saveChunks) }

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
