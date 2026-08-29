package net.aechronis.nodes.tasks

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class SerialSaveQueue(
    private val executor: Executor = ForkJoinPool.commonPool(),
) {
    private val lock = Any()
    private var tail: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    fun current(): CompletableFuture<Void> = synchronized(lock) { tail }

    /** Waits for the latest tail, including work appended while an earlier tail is completing. */
    @Throws(InterruptedException::class, java.util.concurrent.ExecutionException::class, TimeoutException::class)
    fun awaitIdle(
        timeout: Long,
        unit: TimeUnit,
    ) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (true) {
            val observed = current()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Timed out waiting for the serial save queue")
            observed.get(remaining, TimeUnit.NANOSECONDS)
            if (synchronized(lock) { tail === observed }) return
        }
    }

    /** Drains in-flight work but leaves an earlier failure for the next authoritative save to replace. */
    @Throws(InterruptedException::class, TimeoutException::class)
    fun awaitQuiescence(
        timeout: Long,
        unit: TimeUnit,
    ) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (true) {
            val observed = current()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException("Timed out waiting for the serial save queue")
            observed.handle { _, _ -> null }.get(remaining, TimeUnit.NANOSECONDS)
            if (synchronized(lock) { tail === observed }) return
        }
    }

    fun submit(save: () -> Unit): CompletableFuture<Void> = synchronized(lock) {
        tail
            .handle { _, _ -> null }
            .thenRunAsync(save, executor)
            .also { tail = it }
    }
}
