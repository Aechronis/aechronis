package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.aechronis.logger.utils.shutdownExecutor
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private class RollbackFuture<T> : CompletableFuture<T>() {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
}

/** Owns operation admission, callback tracking, and shutdown recovery for a service generation. */
internal class RollbackLifecycle(
    private val executor: ExecutorService,
    private val closeDrainTimeout: Duration,
) : AutoCloseable {
    private val activeInstances = ConcurrentHashMap.newKeySet<UUID>()
    private val activeResults = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val operationIds = ConcurrentHashMap<CompletableFuture<*>, Long>()
    private val recoveryRequiredOperationIds = ConcurrentHashMap.newKeySet<Long>()
    private val externalOperationActive = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val lifecycleLock = Any()
    private val inFlightStages = linkedSetOf<CompletableFuture<Void>>()

    init {
        require(!closeDrainTimeout.isNegative && !closeDrainTimeout.isZero) {
            "Rollback close drain timeout must be positive"
        }
    }

    val isClosing: Boolean
        get() = closing.get()

    fun acquireInstance(instanceUuid: UUID): Boolean = activeInstances.add(instanceUuid)

    fun releaseInstance(instanceUuid: UUID) {
        activeInstances.remove(instanceUuid)
    }

    fun acquireExternalState(): Boolean = externalOperationActive.compareAndSet(false, true)

    fun recordOperation(
        result: CompletableFuture<*>,
        operationId: Long,
    ) {
        operationIds[result] = operationId
    }

    fun requireRecovery(operationId: Long) {
        recoveryRequiredOperationIds += operationId
    }

    fun trackOperation(
        result: CompletableFuture<*>,
        instanceUuid: UUID,
        usesExternalState: Boolean,
    ) {
        result.whenComplete { _, _ ->
            activeInstances.remove(instanceUuid)
            if (usesExternalState) externalOperationActive.compareAndSet(true, false)
        }
    }

    fun acknowledgeRecoveryAsync(): CompletableFuture<Int> =
        synchronized(lifecycleLock) {
            if (closing.get()) return@synchronized CompletableFuture.failedFuture(IllegalStateException("rollback service is closing"))
            if (activeInstances.isNotEmpty() || externalOperationActive.get()) {
                return@synchronized CompletableFuture.failedFuture(IllegalStateException("wait for active operations to finish"))
            }
            Logger.rollback.acknowledgeRecoveryAsync()
        }

    /**
     * Observes a future owned by Minestom, a repository, or an external storage adapter. The
     * drain token is admitted atomically with lifecycle close, but the source callback is attached
     * after releasing [lifecycleLock] so an already-completed future cannot invoke module code
     * while the lifecycle monitor is held.
     */
    fun <T> observeStage(
        isAbandoned: () -> Boolean,
        start: () -> CompletableFuture<T>,
        onAbandoned: (T?, Throwable?) -> Unit = { _, _ -> },
        onComplete: (T?, Throwable?) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val drained = CompletableFuture<Void>()
        var source: CompletableFuture<T>? = null
        var startFailure: Throwable? = null
        val accepted =
            synchronized(lifecycleLock) {
                if (closing.get() || isAbandoned()) {
                    false
                } else {
                    inFlightStages += drained
                    try {
                        source = start()
                    } catch (error: Throwable) {
                        startFailure = error
                    }
                    true
                }
            }

        if (!accepted) {
            onFailure(IllegalStateException("rollback service is closing"))
            return
        }

        fun finishStage(action: () -> Unit) {
            var callbackFailure: Throwable? = null
            try {
                action()
            } catch (error: Throwable) {
                callbackFailure = error
                runCatching { onFailure(error) }.onFailure(error::addSuppressed)
            } finally {
                synchronized(lifecycleLock) { inFlightStages -= drained }
                callbackFailure?.let(drained::completeExceptionally) ?: drained.complete(null)
            }
        }

        startFailure?.let { error ->
            finishStage { onFailure(error) }
            return
        }

        requireNotNull(source).whenComplete { value, failure ->
            finishStage {
                if (closing.get() || isAbandoned()) {
                    onAbandoned(value, failure)
                } else {
                    onComplete(value, failure)
                }
            }
        }
    }

    /**
     * Future-shaped adapter for leaf stages used inside ordered CompletableFuture chains. The
     * tracked token is released only after completing this proxy, so all existing non-async
     * dependants run before lifecycle close can finish.
     */
    fun <T> trackedStage(
        start: () -> CompletableFuture<T>,
        onSettled: (T?, Throwable?) -> Unit = { _, _ -> },
    ): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        observeStage(
            isAbandoned = result::isDone,
            start = start,
            onAbandoned = { value, failure ->
                onSettled(value, failure)
                result.completeExceptionally(IllegalStateException("rollback service is closing"))
            },
            onComplete = { value, failure ->
                onSettled(value, failure)
                if (failure != null) {
                    result.completeExceptionally(failure)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    result.complete(value as T)
                }
            },
            onFailure = result::completeExceptionally,
        )
        return result
    }

    fun <T> trackedResult(): CompletableFuture<T>? =
        synchronized(lifecycleLock) {
            if (closing.get()) return@synchronized null
            val future = RollbackFuture<T>()
            activeResults += future
            future.whenComplete { _, _ ->
                activeResults -= future
                operationIds -= future
            }
            future
        }

    override fun close() {
        val active: Array<CompletableFuture<*>>
        val stages: Array<CompletableFuture<Void>>
        synchronized(lifecycleLock) {
            closing.set(true)
            active = activeResults.toTypedArray()
            recoveryRequiredOperationIds += active.mapNotNull(operationIds::get)
            stages = inFlightStages.toTypedArray()
        }

        // Generation teardown has already cancelled ModuleScheduler work. Complete public results
        // now so a cancelled producer cannot keep this service pending forever; externally-owned
        // source callbacks remain protected by the independent stage drain below.
        active.filterNot { it.isDone }.forEach { result ->
            result.completeExceptionally(IllegalStateException("rollback interrupted by shutdown"))
        }

        try {
            CompletableFuture
                .allOf(*stages)
                .handle { _, _ -> null }
                .get(closeDrainTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for rollback callbacks to quiesce", error)
        } catch (error: TimeoutException) {
            // Do not stop executors or report success. The generation remains loaded and a later
            // close retry can finish after the externally-owned source future settles.
            throw IllegalStateException("Rollback callbacks did not quiesce within $closeDrainTimeout", error)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }

        var failure: Throwable? = null
        var interrupted = false

        fun recordFailure(error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }

        val recoveryMarks =
            recoveryRequiredOperationIds.associateWith { operationId ->
                val mark =
                    try {
                        Logger.rollback.markRecoveryRequiredAsync(operationId)
                    } catch (error: Throwable) {
                        CompletableFuture.failedFuture<Boolean>(error)
                    }
                mark
            }
        if (recoveryMarks.isNotEmpty()) {
            try {
                CompletableFuture.allOf(*recoveryMarks.values.toTypedArray()).get(5, TimeUnit.SECONDS)
                recoveryMarks.forEach { (operationId, mark) ->
                    mark.join()
                    recoveryRequiredOperationIds.remove(operationId)
                }
            } catch (error: InterruptedException) {
                interrupted = true
                recordFailure(IllegalStateException("Interrupted while recording rollback recovery state", error))
            } catch (error: TimeoutException) {
                recordFailure(IllegalStateException("Rollback recovery state was not recorded within 5 seconds", error))
            } catch (error: ExecutionException) {
                recordFailure(error.cause ?: error)
            }
        }

        runCatching { shutdownExecutor(executor, "rollback service") }.onFailure(::recordFailure)
        if (interrupted) Thread.currentThread().interrupt()
        failure?.let { throw it }
    }
}
