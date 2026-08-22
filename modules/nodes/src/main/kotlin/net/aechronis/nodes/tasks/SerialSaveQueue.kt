package net.aechronis.nodes.tasks

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

internal class SerialSaveQueue(
    private val executor: Executor = ForkJoinPool.commonPool(),
) {
    private val lock = Any()
    private var tail: CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    fun current(): CompletableFuture<Void> = synchronized(lock) { tail }

    fun submit(save: () -> Unit): CompletableFuture<Void> = synchronized(lock) {
        tail
            .handle { _, _ -> null }
            .thenRunAsync(save, executor)
            .also { tail = it }
    }
}
