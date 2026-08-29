package net.aechronis.logger.utils

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun <T> awaitLifecycleFuture(
    future: CompletableFuture<T>,
    description: String,
    timeout: Duration = Duration.ofSeconds(10),
): T =
    try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("Interrupted while waiting for $description", error)
    } catch (error: TimeoutException) {
        throw IllegalStateException("$description did not finish within $timeout", error)
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }

internal fun shutdownExecutor(
    executor: ExecutorService,
    description: String,
    gracefulTimeout: Duration = Duration.ofSeconds(5),
    forcedTimeout: Duration = Duration.ofSeconds(2),
) {
    require(!gracefulTimeout.isNegative && !forcedTimeout.isNegative) { "Executor shutdown timeouts cannot be negative" }
    executor.shutdown()
    try {
        if (executor.awaitTermination(gracefulTimeout.toMillis(), TimeUnit.MILLISECONDS)) return
        val abandoned = executor.shutdownNow().size
        check(executor.awaitTermination(forcedTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            "$description did not terminate after interruption ($abandoned queued task(s) abandoned)"
        }
    } catch (error: InterruptedException) {
        executor.shutdownNow()
        Thread.currentThread().interrupt()
        throw IllegalStateException("Interrupted while stopping $description", error)
    }
}
