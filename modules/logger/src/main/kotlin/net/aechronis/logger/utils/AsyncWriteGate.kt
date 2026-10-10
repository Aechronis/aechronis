package net.aechronis.logger.utils

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.ReentrantReadWriteLock

class AsyncWriteGate(
    private val executor: ExecutorService,
    private val description: String,
) : AutoCloseable {
    private val lifecycleLock = ReentrantReadWriteLock()
    private val pendingWrites = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()

    @Volatile
    private var closed = false

    fun submit(task: () -> Unit): CompletableFuture<Void> =
        whileOpen {
            CompletableFuture.runAsync(task, executor).also { future ->
                pendingWrites += future
                future.whenComplete { _, _ -> pendingWrites -= future }
            }
        }

    /** Waits for the writes pending when this method is called, without including later writes. */
    fun flushAsync(): CompletableFuture<Void> = CompletableFuture.allOf(*pendingWrites.toTypedArray())

    /** Admits a read under the same lifecycle lock without making it part of a write flush. */
    fun <T> supply(task: () -> T): CompletableFuture<T> = whileOpen { CompletableFuture.supplyAsync(task, executor) }

    private fun <T> whileOpen(submit: () -> CompletableFuture<T>): CompletableFuture<T> {
        lifecycleLock.readLock().lock()
        try {
            if (closed) return CompletableFuture.failedFuture(IllegalStateException("$description is closed"))
            return submit()
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    override fun close() {
        lifecycleLock.writeLock().lock()
        try {
            closed = true
            shutdownExecutor(executor, description)
        } finally {
            lifecycleLock.writeLock().unlock()
        }
    }
}
