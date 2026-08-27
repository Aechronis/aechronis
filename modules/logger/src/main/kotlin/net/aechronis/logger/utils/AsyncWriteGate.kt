package net.aechronis.logger.utils

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.ReentrantReadWriteLock

class AsyncWriteGate(
    private val executor: ExecutorService,
    private val description: String,
) : AutoCloseable {
    private val lifecycleLock = ReentrantReadWriteLock()

    @Volatile
    private var closed = false

    fun submit(task: () -> Unit): CompletableFuture<Void> {
        lifecycleLock.readLock().lock()
        try {
            if (closed) return CompletableFuture.failedFuture(IllegalStateException("$description is closed"))
            return CompletableFuture.runAsync(task, executor)
        } finally {
            lifecycleLock.readLock().unlock()
        }
    }

    override fun close() {
        lifecycleLock.writeLock().lock()
        try {
            if (closed) return
            closed = true
            executor.close()
        } finally {
            lifecycleLock.writeLock().unlock()
        }
    }
}
