package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.aechronis.logger.params.LookupParams
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.inventory.PlayerInventory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RollbackActor(
    val uuid: UUID,
    val name: String,
)

data class RollbackExecutionResult(
    val kind: RollbackOperationKind,
    val operationId: Long,
    val appliedCount: Int,
    val skippedCount: Int,
)

private class ChunkRestoreTiming(
    private val changeCount: Int,
    private val chunkCount: Int,
) {
    private val startedAt = System.nanoTime()
    private var chunksLoadedAt = startedAt
    private var preparedAt = startedAt
    private var persistedAt = startedAt
    private var applyingAt = startedAt
    private var appliedAt = startedAt

    fun chunksLoaded() {
        chunksLoadedAt = System.nanoTime()
    }

    fun prepared() {
        preparedAt = System.nanoTime()
    }

    fun persisted() {
        persistedAt = System.nanoTime()
    }

    fun applying() {
        applyingAt = System.nanoTime()
    }

    fun applied() {
        appliedAt = System.nanoTime()
    }

    fun completed(
        operationId: Long,
        appliedCount: Int,
        skippedCount: Int,
    ) {
        val completedAt = System.nanoTime()
        println(
            "[Logger] Chunk restore #$operationId completed: chunks=$chunkCount changes=$changeCount " +
                "applied=$appliedCount skipped=$skippedCount; " +
                "load=${millis(chunksLoadedAt - startedAt)}ms " +
                "prepare=${millis(preparedAt - chunksLoadedAt)}ms " +
                "persist=${millis(persistedAt - preparedAt)}ms " +
                "start=${millis(applyingAt - persistedAt)}ms " +
                "apply=${millis(appliedAt - applyingAt)}ms " +
                "finalize=${millis(completedAt - appliedAt)}ms " +
                "total=${millis(completedAt - startedAt)}ms",
        )
    }

    private fun millis(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanos)
}

/** Coordinates persisted apply/undo/redo transitions across the rollback components. */
class RollbackService(
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
    closeDrainTimeout: Duration = Duration.ofSeconds(5),
) : AutoCloseable {
    private val lifecycle = RollbackLifecycle(executor, closeDrainTimeout)
    private val planner = RollbackPlanner(executor)
    private val preparation = RollbackPreparation()
    private val stateWriter = RollbackExternalChanges(lifecycle)
    private val worldWriter = RollbackWorldChanges(executor, lifecycle, stateWriter)
    private val recovery = RollbackRecovery(lifecycle, worldWriter, stateWriter)

    fun computePlanAsync(
        kind: RollbackOperationKind,
        params: LookupParams,
        targetTs: Long,
        instanceUuid: UUID,
        center: Pos,
        safeMode: Boolean,
        selection: RollbackSelection = RollbackSelection(),
    ): CompletableFuture<RollbackPlan> = planner.computePlanAsync(kind, params, targetTs, instanceUuid, center, safeMode, selection)

    fun applyAsync(
        actor: RollbackActor,
        plan: RollbackPlan,
    ): CompletableFuture<RollbackExecutionResult> {
        val result =
            lifecycle.trackedResult<RollbackExecutionResult>()
                ?: return CompletableFuture.failedFuture(IllegalStateException("rollback service is closing"))
        val timing =
            if (plan.kind == RollbackOperationKind.CHUNK_RESTORE) {
                ChunkRestoreTiming(
                    changeCount = plan.blockChanges.size,
                    chunkCount =
                        plan.blockChanges
                            .map { (it.x shr 4) to (it.z shr 4) }
                            .toSet()
                            .size,
                )
            } else {
                null
            }
        beginApply(actor, plan, result, timing)
        return result
    }

    fun restoreSnapshotAsync(
        actor: RollbackActor,
        snapshot: InventorySnapshot,
    ): CompletableFuture<RollbackExecutionResult> {
        if (snapshot.items.size != PlayerInventory.INVENTORY_SIZE) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException(
                    "snapshot ${snapshot.id} has incompatible inventory size ${snapshot.items.size}",
                ),
            )
        }
        val player =
            MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(snapshot.playerUuid)
                ?: return CompletableFuture.failedFuture(
                    IllegalStateException("player ${snapshot.playerName} must be online"),
                )
        val instance =
            player.instance
                ?: return CompletableFuture.failedFuture(
                    IllegalStateException("player ${snapshot.playerName} is not in an instance"),
                )
        val plan =
            RollbackPlan(
                kind = RollbackOperationKind.SNAPSHOT,
                instanceUuid = instance.uuid,
                targetTs = snapshot.timestamp,
                queryDesc = "inventory snapshot #${snapshot.id} (${snapshot.action.value}) for ${snapshot.playerName}",
                safeMode = false,
                blockChanges = emptyList(),
                inventoryChanges =
                    survivalInventorySlots.map { slot ->
                        InventoryChangePlan(
                            inventoryLogId = null,
                            playerUuid = snapshot.playerUuid,
                            slot = slot,
                            expectedItem = null,
                            targetItem = snapshot.items[slot],
                        )
                    },
                skippedBlockCount = 0,
            )
        return applyAsync(actor, plan)
    }

    private fun beginApply(
        actor: RollbackActor,
        plan: RollbackPlan,
        result: CompletableFuture<RollbackExecutionResult>,
        timing: ChunkRestoreTiming?,
    ) {
        if (lifecycle.isClosing || result.isDone) return
        if (!lifecycle.acquireInstance(plan.instanceUuid)) {
            result.completeExceptionally(IllegalStateException("another rollback is already running in this instance"))
            return
        }
        val usesExternalState = plan.storageChanges.isNotEmpty() || plan.inventoryChanges.isNotEmpty()
        if (usesExternalState && !lifecycle.acquireExternalState()) {
            lifecycle.releaseInstance(plan.instanceUuid)
            result.completeExceptionally(IllegalStateException("another storage or inventory operation is already running"))
            return
        }
        lifecycle.trackOperation(result, plan.instanceUuid, usesExternalState)
        val instance = MinecraftServer.getInstanceManager().getInstance(plan.instanceUuid)
        if (instance == null) {
            result.completeExceptionally(IllegalStateException("instance no longer exists"))
            return
        }

        lifecycle.observeStage(
            isAbandoned = result::isDone,
            start = { Logger.rollback.hasRecoveryRequiredAsync(plan.instanceUuid, includeGlobalState = usesExternalState) },
            onComplete = { recoveryRequired, recoveryFailure ->
                when {
                    recoveryFailure != null -> result.completeExceptionally(recoveryFailure)
                    recoveryRequired == true -> {
                        result.completeExceptionally(
                            IllegalStateException("recovery acknowledgement is required before further operations"),
                        )
                    }

                    else -> {
                        lifecycle.observeStage(
                            isAbandoned = result::isDone,
                            start = { worldWriter.loadChunks(instance, plan.blockChanges) },
                            onComplete = { _, loadFailure ->
                                if (loadFailure != null) {
                                    result.completeExceptionally(loadFailure)
                                } else {
                                    timing?.chunksLoaded()
                                    ModuleScheduler.scheduleNextTick {
                                        prepareAndPersist(actor, instance, plan, result, timing)
                                    }
                                }
                            },
                            onFailure = result::completeExceptionally,
                        )
                    }
                }
            },
            onFailure = result::completeExceptionally,
        )
    }

    fun undoAsync(actor: RollbackActor): CompletableFuture<RollbackExecutionResult> = replayLatest(actor, undo = true)

    fun redoAsync(actor: RollbackActor): CompletableFuture<RollbackExecutionResult> = replayLatest(actor, undo = false)

    fun acknowledgeRecoveryAsync(): CompletableFuture<Int> = lifecycle.acknowledgeRecoveryAsync()

    private fun prepareAndPersist(
        actor: RollbackActor,
        instance: Instance,
        plan: RollbackPlan,
        result: CompletableFuture<RollbackExecutionResult>,
        timing: ChunkRestoreTiming?,
        preparedChunkRestore: PreparedRollbackChanges? = null,
    ) {
        if (lifecycle.isClosing || result.isDone) return
        if (
            plan.kind == RollbackOperationKind.CHUNK_RESTORE &&
            plan.storageChanges.isEmpty() &&
            plan.inventoryChanges.isEmpty() &&
            plan.entityChanges.isEmpty() &&
            preparedChunkRestore == null
        ) {
            lifecycle.observeStage(
                isAbandoned = result::isDone,
                start = { CompletableFuture.supplyAsync({ preparation.prepareChunkRestoreChanges(plan) }, executor) },
                onComplete = { prepared, failure ->
                    if (failure != null) {
                        result.completeExceptionally(failure)
                    } else {
                        prepareAndPersist(actor, instance, plan, result, timing, requireNotNull(prepared))
                    }
                },
                onFailure = result::completeExceptionally,
            )
            return
        }
        val prepared =
            try {
                preparation.prepare(instance, plan, preparedChunkRestore)
            } catch (exception: Exception) {
                result.completeExceptionally(exception)
                return
            }
        val changes = prepared.changes
        val skipped = prepared.skippedCount
        timing?.prepared()

        if (changes.isEmpty()) {
            result.complete(RollbackExecutionResult(plan.kind, 0, 0, skipped))
            return
        }

        val operation =
            RollbackOperation(
                timestamp = System.currentTimeMillis(),
                actorUuid = actor.uuid,
                actorName = actor.name,
                instanceUuid = plan.instanceUuid,
                kind = plan.kind,
                queryDesc = plan.queryDesc,
                targetTs = plan.targetTs,
                safeMode = plan.safeMode,
                status = RollbackStatus.PREPARED,
                blockChangeCount = 0,
                skippedChangeCount = skipped,
            )

        lifecycle.observeStage(
            isAbandoned = result::isDone,
            start = { Logger.rollback.insertOperationAsync(operation, changes) },
            onAbandoned = { operationId, persistFailure ->
                if (persistFailure == null && operationId != null) lifecycle.requireRecovery(operationId)
            },
            onComplete = { operationIdValue, persistFailure ->
                if (persistFailure != null) {
                    result.completeExceptionally(persistFailure)
                } else {
                    val operationId = requireNotNull(operationIdValue)
                    lifecycle.recordOperation(result, operationId)
                    timing?.persisted()
                    lifecycle.observeStage(
                        isAbandoned = result::isDone,
                        start = {
                            Logger.rollback.startOperationAsync(
                                operationId,
                                RollbackStatus.PREPARED,
                                RollbackStatus.APPLYING,
                                changes,
                                expectedRolledBack = plan.kind == RollbackOperationKind.RESTORE,
                            )
                        },
                        onComplete = { _, transitionFailure ->
                            if (transitionFailure != null) {
                                recovery.completeFailureAfterStatus(operationId, RollbackStatus.FAILED, transitionFailure, result)
                            } else {
                                timing?.applying()
                                ModuleScheduler.scheduleNextTick {
                                    applyBatches(instance, operationId, plan, changes, skipped, result, timing)
                                }
                            }
                        },
                        onFailure = result::completeExceptionally,
                    )
                }
            },
            onFailure = result::completeExceptionally,
        )
    }

    private fun applyBatches(
        instance: Instance,
        operationId: Long,
        plan: RollbackPlan,
        changes: List<RollbackChange>,
        initialSkipped: Int,
        result: CompletableFuture<RollbackExecutionResult>,
        timing: ChunkRestoreTiming?,
    ) {
        if (lifecycle.isClosing || result.isDone) return
        val worldChanges =
            changes.filter {
                it.changeKind == RollbackChangeKind.BLOCK || it.changeKind == RollbackChangeKind.STORAGE
            }
        val inventoryChanges = changes.filter { it.changeKind == RollbackChangeKind.INVENTORY }
        val entityChanges = changes.filter { it.changeKind == RollbackChangeKind.ENTITY }
        val appliedWorld = mutableListOf<RollbackChange>()
        val worldResult =
            if (
                plan.kind == RollbackOperationKind.CHUNK_RESTORE &&
                worldChanges.all { it.changeKind == RollbackChangeKind.BLOCK }
            ) {
                worldWriter.applyChunkRestoreWorldChanges(
                    instance,
                    worldChanges,
                    reverse = false,
                    tolerateConflicts = true,
                    appliedWorld,
                )
            } else {
                worldWriter.applyWorldChanges(
                    instance,
                    worldChanges,
                    reverse = false,
                    tolerateUnlinkedBlockConflicts = true,
                    appliedWorld,
                )
            }
        worldResult.whenComplete { runtimeSkipped, worldFailure ->
            if (result.isDone) return@whenComplete
            if (worldFailure != null) {
                recovery.finishApplyFailure(
                    operationId,
                    worldFailure,
                    recovery.compensateApplied(instance, appliedWorld, emptyList(), emptyList(), reverseExternal = true),
                    result,
                    forceRecovery = worldWriter.isStorageWorldChangeFailure(worldFailure),
                )
                return@whenComplete
            }
            val skipped = initialSkipped + runtimeSkipped
            val appliedInventory = mutableListOf<RollbackChange>()
            stateWriter.applyInventory(inventoryChanges, reverse = false, appliedInventory).whenComplete { _, inventoryFailure ->
                if (result.isDone) return@whenComplete
                if (inventoryFailure != null) {
                    recovery.finishApplyFailure(
                        operationId,
                        inventoryFailure,
                        recovery.compensateApplied(
                            instance,
                            appliedWorld,
                            appliedInventory,
                            emptyList(),
                            reverseExternal = true,
                        ),
                        result,
                    )
                    return@whenComplete
                }

                val appliedEntities = mutableListOf<RollbackChange>()
                stateWriter.applyEntities(instance, entityChanges, reverse = false, appliedEntities).whenComplete { _, entityFailure ->
                    if (result.isDone) return@whenComplete
                    if (entityFailure != null) {
                        recovery.finishApplyFailure(
                            operationId,
                            entityFailure,
                            recovery.compensateApplied(
                                instance,
                                appliedWorld,
                                appliedInventory,
                                appliedEntities,
                                reverseExternal = true,
                            ),
                            result,
                        )
                        return@whenComplete
                    }

                    val applied = appliedWorld + appliedInventory + appliedEntities
                    val rolledBack = plan.kind == RollbackOperationKind.ROLLBACK
                    timing?.applied()
                    lifecycle.observeStage(
                        isAbandoned = result::isDone,
                        start = { Logger.rollback.completeOperationAsync(operationId, applied, rolledBack, skipped) },
                        onComplete = { _, failure ->
                            if (failure == null) {
                                timing?.completed(operationId, applied.size, skipped)
                                result.complete(RollbackExecutionResult(plan.kind, operationId, applied.size, skipped))
                            } else {
                                val compensation =
                                    recovery.compensateApplied(
                                        instance,
                                        appliedWorld,
                                        appliedInventory,
                                        appliedEntities,
                                        reverseExternal = true,
                                    )
                                recovery.finishApplyFailure(operationId, failure, compensation, result, forceRecovery = true)
                            }
                        },
                        onFailure = result::completeExceptionally,
                    )
                }
            }
        }
    }

    private fun replayLatest(
        actor: RollbackActor,
        undo: Boolean,
    ): CompletableFuture<RollbackExecutionResult> {
        val result =
            lifecycle.trackedResult<RollbackExecutionResult>()
                ?: return CompletableFuture.failedFuture(IllegalStateException("rollback service is closing"))
        lifecycle.observeStage(
            isAbandoned = result::isDone,
            start = { Logger.rollback.findLatestOperationAsync(actor.uuid) },
            onComplete = { operation, lookupFailure ->
                val requiredStatus = if (undo) RollbackStatus.APPLIED else RollbackStatus.UNDONE
                when {
                    lookupFailure != null -> result.completeExceptionally(lookupFailure)
                    operation == null || operation.status != requiredStatus || operation.kind == RollbackOperationKind.LEGACY -> {
                        result.completeExceptionally(IllegalStateException(if (undo) "nothing to undo" else "nothing to redo"))
                    }

                    else -> beginReplay(operation, requiredStatus, undo, result)
                }
            },
            onFailure = result::completeExceptionally,
        )
        return result
    }

    private fun beginReplay(
        operation: RollbackOperation,
        requiredStatus: RollbackStatus,
        undo: Boolean,
        result: CompletableFuture<RollbackExecutionResult>,
    ) {
        if (lifecycle.isClosing || result.isDone) return
        if (!lifecycle.acquireInstance(operation.instanceUuid)) {
            result.completeExceptionally(IllegalStateException("another rollback is already running in this instance"))
            return
        }
        val instance = MinecraftServer.getInstanceManager().getInstance(operation.instanceUuid)
        if (instance == null) {
            lifecycle.releaseInstance(operation.instanceUuid)
            result.completeExceptionally(IllegalStateException("instance no longer exists"))
            return
        }
        lifecycle.observeStage(
            isAbandoned = result::isDone,
            start = { Logger.rollback.findChangesAsync(operation.id, appliedOnly = true) },
            onComplete = { storedValue, changesFailure ->
                if (changesFailure != null) {
                    lifecycle.releaseInstance(operation.instanceUuid)
                    result.completeExceptionally(changesFailure)
                } else {
                    val stored = requireNotNull(storedValue)
                    val usesExternalState =
                        stored.any { it.changeKind == RollbackChangeKind.STORAGE || it.changeKind == RollbackChangeKind.INVENTORY }
                    if (usesExternalState && !lifecycle.acquireExternalState()) {
                        lifecycle.releaseInstance(operation.instanceUuid)
                        result.completeExceptionally(IllegalStateException("another storage or inventory operation is already running"))
                    } else {
                        lifecycle.recordOperation(result, operation.id)
                        lifecycle.trackOperation(result, operation.instanceUuid, usesExternalState)
                        val ordered = if (undo) stored.asReversed() else stored
                        continueReplay(operation, requiredStatus, undo, instance, ordered, result, usesExternalState)
                    }
                }
            },
            onFailure = result::completeExceptionally,
        )
    }

    private fun continueReplay(
        operation: RollbackOperation,
        requiredStatus: RollbackStatus,
        undo: Boolean,
        instance: Instance,
        ordered: List<RollbackChange>,
        result: CompletableFuture<RollbackExecutionResult>,
        usesExternalState: Boolean,
    ) {
        lifecycle.observeStage(
            isAbandoned = result::isDone,
            start = { Logger.rollback.hasRecoveryRequiredAsync(operation.instanceUuid, includeGlobalState = usesExternalState) },
            onComplete = { recoveryRequired, recoveryFailure ->
                when {
                    recoveryFailure != null -> result.completeExceptionally(recoveryFailure)
                    recoveryRequired == true -> {
                        result.completeExceptionally(
                            IllegalStateException("recovery acknowledgement is required before further operations"),
                        )
                    }

                    else -> {
                        lifecycle.observeStage(
                            isAbandoned = result::isDone,
                            start = { worldWriter.loadChunksFromChanges(instance, ordered) },
                            onComplete = { _, loadFailure ->
                                if (loadFailure != null) {
                                    result.completeExceptionally(loadFailure)
                                } else {
                                    startReplayTransition(operation, requiredStatus, undo, instance, ordered, result)
                                }
                            },
                            onFailure = result::completeExceptionally,
                        )
                    }
                }
            },
            onFailure = result::completeExceptionally,
        )
    }

    private fun startReplayTransition(
        operation: RollbackOperation,
        requiredStatus: RollbackStatus,
        undo: Boolean,
        instance: Instance,
        ordered: List<RollbackChange>,
        result: CompletableFuture<RollbackExecutionResult>,
    ) {
        val transition = if (undo) RollbackStatus.UNDOING else RollbackStatus.REDOING
        val targetRolledBack =
            if (undo) operation.kind == RollbackOperationKind.RESTORE else operation.kind == RollbackOperationKind.ROLLBACK
        lifecycle.observeStage(
            isAbandoned = result::isDone,
            start = {
                Logger.rollback.startOperationAsync(
                    operation.id,
                    requiredStatus,
                    transition,
                    ordered,
                    expectedRolledBack = !targetRolledBack,
                )
            },
            onComplete = { _, transitionFailure ->
                if (transitionFailure != null) {
                    result.completeExceptionally(transitionFailure)
                } else {
                    ModuleScheduler.scheduleNextTick {
                        if (!lifecycle.isClosing && !result.isDone) replayBatches(instance, operation, ordered, undo, result)
                    }
                }
            },
            onFailure = result::completeExceptionally,
        )
    }

    private fun replayBatches(
        instance: Instance,
        operation: RollbackOperation,
        changes: List<RollbackChange>,
        undo: Boolean,
        result: CompletableFuture<RollbackExecutionResult>,
    ) {
        if (lifecycle.isClosing || result.isDone) return
        val worldChanges =
            changes.filter {
                it.changeKind == RollbackChangeKind.BLOCK || it.changeKind == RollbackChangeKind.STORAGE
            }
        val inventoryChanges = changes.filter { it.changeKind == RollbackChangeKind.INVENTORY }
        val entityChanges = changes.filter { it.changeKind == RollbackChangeKind.ENTITY }
        val appliedWorld = mutableListOf<RollbackChange>()
        val worldResult =
            if (
                operation.kind == RollbackOperationKind.CHUNK_RESTORE &&
                worldChanges.all { it.changeKind == RollbackChangeKind.BLOCK }
            ) {
                worldWriter.applyChunkRestoreWorldChanges(
                    instance,
                    worldChanges,
                    reverse = undo,
                    tolerateConflicts = false,
                    appliedWorld,
                )
            } else {
                worldWriter.applyWorldChanges(
                    instance,
                    worldChanges,
                    reverse = undo,
                    tolerateUnlinkedBlockConflicts = false,
                    appliedWorld,
                )
            }
        worldResult.whenComplete { _, worldFailure ->
            if (result.isDone) return@whenComplete
            if (worldFailure != null) {
                recovery.finishReplayFailure(
                    operation.id,
                    undo,
                    worldFailure,
                    recovery.compensateApplied(
                        instance,
                        appliedWorld,
                        emptyList(),
                        emptyList(),
                        reverseExternal = !undo,
                    ),
                    result,
                    forceRecovery = worldWriter.isStorageWorldChangeFailure(worldFailure),
                )
                return@whenComplete
            }

            val appliedInventory = mutableListOf<RollbackChange>()
            stateWriter.applyInventory(inventoryChanges, reverse = undo, appliedInventory).whenComplete { _, inventoryFailure ->
                if (result.isDone) return@whenComplete
                if (inventoryFailure != null) {
                    recovery.finishReplayFailure(
                        operation.id,
                        undo,
                        inventoryFailure,
                        recovery.compensateApplied(
                            instance,
                            appliedWorld,
                            appliedInventory,
                            emptyList(),
                            reverseExternal = !undo,
                        ),
                        result,
                    )
                    return@whenComplete
                }

                val appliedEntities = mutableListOf<RollbackChange>()
                stateWriter.applyEntities(instance, entityChanges, reverse = undo, appliedEntities).whenComplete { _, entityFailure ->
                    if (result.isDone) return@whenComplete
                    if (entityFailure != null) {
                        recovery.finishReplayFailure(
                            operation.id,
                            undo,
                            entityFailure,
                            recovery.compensateApplied(
                                instance,
                                appliedWorld,
                                appliedInventory,
                                appliedEntities,
                                reverseExternal = !undo,
                            ),
                            result,
                        )
                        return@whenComplete
                    }

                    val transition = if (undo) RollbackStatus.UNDOING else RollbackStatus.REDOING
                    val targetStatus = if (undo) RollbackStatus.UNDONE else RollbackStatus.APPLIED
                    val targetRolledBack =
                        if (undo) {
                            operation.kind == RollbackOperationKind.RESTORE
                        } else {
                            operation.kind == RollbackOperationKind.ROLLBACK
                        }
                    lifecycle.observeStage(
                        isAbandoned = result::isDone,
                        start = { Logger.rollback.completeReplayAsync(operation, transition, targetStatus, targetRolledBack) },
                        onComplete = { _, failure ->
                            if (failure == null) {
                                result.complete(
                                    RollbackExecutionResult(
                                        operation.kind,
                                        operation.id,
                                        appliedWorld.size + appliedInventory.size + appliedEntities.size,
                                        0,
                                    ),
                                )
                            } else {
                                val compensation =
                                    recovery.compensateApplied(
                                        instance,
                                        appliedWorld,
                                        appliedInventory,
                                        appliedEntities,
                                        reverseExternal = !undo,
                                    )
                                recovery.finishReplayFailure(operation.id, undo, failure, compensation, result, forceRecovery = true)
                            }
                        },
                        onFailure = result::completeExceptionally,
                    )
                }
            }
        }
    }

    override fun close() = lifecycle.close()
}
