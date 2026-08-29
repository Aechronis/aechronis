package net.aechronis.server.modules

import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Tracks the real module futures while exposing a separately bounded future to periodic callers. */
internal class ModuleCheckpointTracker(
    private val callerTimeout: Duration = Duration.ofSeconds(60),
    private val lifecycleTimeout: Duration = Duration.ofSeconds(75),
) {
    private val lock = Any()
    private var active: CompletableFuture<Void>? = null
    private var retainedFailure: Throwable? = null

    fun request(start: () -> CompletableFuture<Void>): CompletableFuture<Void> =
        synchronized(lock) {
            settleCompletedCheckpoint()
            active?.let { return@synchronized boundedView(it) }

            val checkpoint =
                try {
                    start()
                } catch (error: Throwable) {
                    CompletableFuture.failedFuture(error)
                }
            active = checkpoint
            boundedView(checkpoint)
        }

    fun awaitCompletion() {
        awaitCheckpoint(propagateFailure = true)
        synchronized(lock) { retainedFailure }?.let { throw it }
    }

    /** Waits for underlying work to stop, retaining a completed failure for an authoritative retry. */
    fun awaitQuiescence() {
        awaitCheckpoint(propagateFailure = false)
    }

    /** Marks a successful synchronous generation save as the replacement for any failed checkpoint. */
    fun settleAfterAuthoritativeSave() {
        synchronized(lock) {
            settleCompletedCheckpoint()
            check(active == null) { "Cannot settle a checkpoint failure while module save work is still running" }
            retainedFailure = null
        }
    }

    private fun awaitCheckpoint(propagateFailure: Boolean) {
        val checkpoint =
            synchronized(lock) {
                settleCompletedCheckpoint()
                active
            }
        if (checkpoint != null) {
            try {
                checkpoint.get(lifecycleTimeout.toMillis(), TimeUnit.MILLISECONDS)
                recordCompletion(checkpoint, null)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while waiting for module checkpoints", error)
            } catch (error: TimeoutException) {
                throw IllegalStateException("Module checkpoints did not finish within $lifecycleTimeout", error)
            } catch (error: ExecutionException) {
                val cause = unwrap(error)
                recordCompletion(checkpoint, cause)
                if (propagateFailure) throw cause
            } catch (error: CancellationException) {
                recordCompletion(checkpoint, error)
                if (propagateFailure) throw error
            }
        }
    }

    fun hasPending(): Boolean = synchronized(lock) { active?.isDone == false }

    private fun settleCompletedCheckpoint() {
        val checkpoint = active ?: return
        if (!checkpoint.isDone) return
        val failure =
            try {
                checkpoint.join()
                null
            } catch (error: CompletionException) {
                unwrap(error)
            } catch (error: CancellationException) {
                error
            }
        active = null
        retainedFailure = failure
    }

    private fun recordCompletion(
        checkpoint: CompletableFuture<Void>,
        failure: Throwable?,
    ) {
        synchronized(lock) {
            if (active !== checkpoint) return
            active = null
            retainedFailure = failure
        }
    }

    private fun boundedView(checkpoint: CompletableFuture<Void>): CompletableFuture<Void> {
        val bounded = CompletableFuture<Void>()
        checkpoint.whenComplete { _, error ->
            if (error == null) bounded.complete(null) else bounded.completeExceptionally(error)
        }
        return bounded.orTimeout(callerTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun unwrap(error: Throwable): Throwable {
        val cause = error.cause
        return if ((error is CompletionException || error is ExecutionException) && cause != null) unwrap(cause) else error
    }
}
