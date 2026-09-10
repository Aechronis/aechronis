package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.minestom.server.instance.Instance
import java.util.concurrent.CompletableFuture

/** Reverses partial mutations and persists the resulting failure or recovery status. */
internal class RollbackRecovery(
    private val lifecycle: RollbackLifecycle,
    private val worldWriter: RollbackWorldChanges,
    private val stateWriter: RollbackExternalChanges,
) {
    fun compensateApplied(
        instance: Instance,
        worldChanges: List<RollbackChange>,
        inventoryChanges: List<RollbackChange>,
        entityChanges: List<RollbackChange>,
        reverseExternal: Boolean,
    ): CompletableFuture<Void> {
        val failures = mutableListOf<Throwable>()
        var chain = CompletableFuture.completedFuture<Void>(null)
        chain =
            chain.thenCompose {
                captureCompensation(
                    stateWriter.applyEntities(instance, entityChanges.asReversed(), reverseExternal, mutableListOf()),
                    failures,
                )
            }
        chain =
            chain.thenCompose {
                captureCompensation(
                    stateWriter.applyInventory(inventoryChanges.asReversed(), reverseExternal, mutableListOf()),
                    failures,
                )
            }
        chain =
            chain.thenCompose {
                captureCompensation(
                    worldWriter
                        .applyWorldChanges(
                            instance,
                            worldChanges.asReversed(),
                            reverse = reverseExternal,
                            tolerateUnlinkedBlockConflicts = false,
                            mutableListOf(),
                        ).thenAccept {},
                    failures,
                )
            }
        return chain.thenApply {
            if (failures.isNotEmpty()) {
                val failure = IllegalStateException("rollback compensation failed")
                failures.forEach(failure::addSuppressed)
                throw failure
            }
            null
        }
    }

    private fun captureCompensation(
        future: CompletableFuture<Void>,
        failures: MutableList<Throwable>,
    ): CompletableFuture<Void> {
        val captured = CompletableFuture<Void>()
        future.whenComplete { _, failure ->
            if (failure != null) failures += failure
            captured.complete(null)
        }
        return captured
    }

    fun finishApplyFailure(
        operationId: Long,
        failure: Throwable,
        compensation: CompletableFuture<Void>,
        result: CompletableFuture<RollbackExecutionResult>,
        forceRecovery: Boolean = false,
    ) {
        compensation.whenComplete { _, compensationFailure ->
            if (lifecycle.isClosing || result.isDone) return@whenComplete
            if (compensationFailure != null) failure.addSuppressed(compensationFailure)
            val status =
                if (forceRecovery || compensationFailure != null) RollbackStatus.RECOVERY_REQUIRED else RollbackStatus.FAILED
            completeFailureAfterStatus(operationId, status, failure, result)
        }
    }

    fun finishReplayFailure(
        operationId: Long,
        undo: Boolean,
        failure: Throwable,
        compensation: CompletableFuture<Void>,
        result: CompletableFuture<RollbackExecutionResult>,
        forceRecovery: Boolean = false,
    ) {
        compensation.whenComplete { _, compensationFailure ->
            if (lifecycle.isClosing || result.isDone) return@whenComplete
            if (compensationFailure != null) failure.addSuppressed(compensationFailure)
            val status =
                if (forceRecovery || compensationFailure != null) {
                    RollbackStatus.RECOVERY_REQUIRED
                } else if (undo) {
                    RollbackStatus.APPLIED
                } else {
                    RollbackStatus.UNDONE
                }
            completeFailureAfterStatus(operationId, status, failure, result)
        }
    }

    fun completeFailureAfterStatus(
        operationId: Long,
        status: RollbackStatus,
        failure: Throwable,
        result: CompletableFuture<RollbackExecutionResult>,
    ) {
        if (lifecycle.isClosing || result.isDone) return
        lifecycle.observeStage(
            isAbandoned = result::isDone,
            start = { Logger.rollback.updateStatusAsync(operationId, status) },
            onComplete = { _, statusFailure ->
                if (statusFailure != null) failure.addSuppressed(statusFailure)
                result.completeExceptionally(failure)
            },
            onFailure = result::completeExceptionally,
        )
    }
}
