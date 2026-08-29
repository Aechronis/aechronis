package io.github.openminigameserver.worldedit.platform.misc

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object WorldEditExecutor {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor {
            Thread(it, "WorldEdit-Operation-Queue").apply { isDaemon = true }
        }
    private val accepting = AtomicBoolean(true)

    /** Returns false once lifecycle quiescence has stopped accepting new operations. */
    fun submit(operation: () -> Unit): Boolean {
        if (!accepting.get()) return false
        return try {
            executor.submit(operation)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    fun shutdown() {
        accepting.set(false)
        executor.shutdown()
        try {
            if (!executor.awaitTermination(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                check(executor.awaitTermination(FORCED_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                    "WorldEdit operation queue did not terminate after interruption"
                }
            }
        } catch (error: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while stopping the WorldEdit operation queue", error)
        }
    }

    private const val GRACEFUL_SHUTDOWN_SECONDS = 10L
    private const val FORCED_SHUTDOWN_SECONDS = 5L
}
