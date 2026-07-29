package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.VanillaStorage
import net.aechronis.logger.params.LookupParams
import net.aechronis.logger.utils.ItemCodec
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.inventory.PlayerInventory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

private class RollbackFuture<T> : CompletableFuture<T>() {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
}

private data class InventoryWrite(
    val change: RollbackChange,
    val slot: Int,
    val before: net.minestom.server.item.ItemStack,
    val after: net.minestom.server.item.ItemStack,
)

class RollbackService(
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {
    private val activeInstances = ConcurrentHashMap.newKeySet<UUID>()
    private val activeResults = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val operationIds = ConcurrentHashMap<CompletableFuture<*>, Long>()
    private val externalOperationActive = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val lifecycleLock = Any()

    fun computePlanAsync(
        kind: RollbackOperationKind,
        params: LookupParams,
        targetTs: Long,
        instanceUuid: UUID,
        center: Pos,
        safeMode: Boolean,
        selection: RollbackSelection = RollbackSelection(),
    ): CompletableFuture<RollbackPlan> {
        check(kind == RollbackOperationKind.ROLLBACK || kind == RollbackOperationKind.RESTORE) {
            "only rollback and restore operations can be planned from history"
        }
        val blockRowsFuture =
            if (RollbackDomain.BLOCK in selection.domains) {
                when (kind) {
                    RollbackOperationKind.ROLLBACK -> {
                        Logger.repository.searchForRollbackAsync(
                            params,
                            targetTs,
                            instanceUuid,
                            center.blockX(),
                            center.blockY(),
                            center.blockZ(),
                        )
                    }

                    RollbackOperationKind.RESTORE -> {
                        Logger.repository.searchForRestoreAsync(
                            params,
                            targetTs,
                            instanceUuid,
                            center.blockX(),
                            center.blockY(),
                            center.blockZ(),
                        )
                    }

                    RollbackOperationKind.LEGACY -> {
                        error("unreachable")
                    }

                    RollbackOperationKind.SNAPSHOT -> {
                        error("unreachable")
                    }
                }
            } else {
                CompletableFuture.completedFuture(emptyList())
            }
        val storageRowsFuture =
            if (RollbackDomain.STORAGE in selection.domains) {
                Logger.storageChange.searchForOperationAsync(
                    params,
                    targetTs,
                    selection.storageActions,
                    rolledBack = kind == RollbackOperationKind.RESTORE,
                    limit = Int.MAX_VALUE,
                )
            } else {
                CompletableFuture.completedFuture(emptyList())
            }
        val inventoryRowsFuture =
            if (RollbackDomain.INVENTORY in selection.domains) {
                Logger.inventoryChange.searchForOperationAsync(
                    params,
                    targetTs,
                    selection.inventoryActions,
                    rolledBack = kind == RollbackOperationKind.RESTORE,
                    limit = Int.MAX_VALUE,
                )
            } else {
                CompletableFuture.completedFuture(emptyList())
            }
        val entityRowsFuture =
            if (RollbackDomain.ENTITY in selection.domains) {
                Logger.entityChange.searchForOperationAsync(
                    params,
                    targetTs,
                    selection.entityActions,
                    rolledBack = kind == RollbackOperationKind.RESTORE,
                    instanceUuid = instanceUuid,
                    center = center,
                    limit = Int.MAX_VALUE,
                )
            } else {
                CompletableFuture.completedFuture(emptyList())
            }
        return CompletableFuture
            .allOf(
                blockRowsFuture,
                storageRowsFuture,
                inventoryRowsFuture,
                entityRowsFuture,
            ).thenApplyAsync({
                val rows = blockRowsFuture.join()
                val storageRows = storageRowsFuture.join()
                val inventoryRows = inventoryRowsFuture.join()
                val entityRows = entityRowsFuture.join()
                val selected = rows
                val blockChanges =
                    selected.map { row ->
                        if (kind == RollbackOperationKind.ROLLBACK) {
                            BlockChangePlan(
                                row.id,
                                row.x,
                                row.y,
                                row.z,
                                row.blockNewState,
                                row.blockNew,
                                row.blockNewNbt,
                                row.blockOldState,
                                row.blockOld,
                                row.blockOldNbt,
                            )
                        } else {
                            BlockChangePlan(
                                row.id,
                                row.x,
                                row.y,
                                row.z,
                                row.blockOldState,
                                row.blockOld,
                                row.blockOldNbt,
                                row.blockNewState,
                                row.blockNew,
                                row.blockNewNbt,
                            )
                        }
                    }
                val selectedStorage = storageRows
                val storageChanges =
                    selectedStorage.map { row ->
                        StorageChangePlan(
                            storageLogId = row.id,
                            source = row.source,
                            storageId = row.storageId,
                            slot = row.slot,
                            targetAction = if (kind == RollbackOperationKind.ROLLBACK) row.action.inverse() else row.action,
                            item = row.item,
                            amount = row.amount,
                        )
                    }
                val inventoryChanges =
                    inventoryRows.map { row ->
                        InventoryChangePlan(
                            inventoryLogId = row.id,
                            playerUuid = row.playerUuid,
                            slot = row.slot,
                            expectedItem = if (kind == RollbackOperationKind.ROLLBACK) row.newItem else row.oldItem,
                            targetItem = if (kind == RollbackOperationKind.ROLLBACK) row.oldItem else row.newItem,
                        )
                    }
                val entityChanges =
                    entityRows.map { row ->
                        EntityChangePlan(
                            entityLogId = row.id,
                            entityUuid = row.entityUuid,
                            entityType = row.entityType,
                            targetAction = if (kind == RollbackOperationKind.ROLLBACK) row.action.inverse() else row.action,
                            position = row.position,
                            velocity = row.velocity,
                            tagData = row.tagData,
                        )
                    }
                RollbackPlan(
                    kind = kind,
                    instanceUuid = instanceUuid,
                    targetTs = targetTs,
                    queryDesc = params.human(),
                    safeMode = safeMode,
                    blockChanges = blockChanges,
                    storageChanges = storageChanges,
                    inventoryChanges = inventoryChanges,
                    entityChanges = entityChanges,
                    skippedBlockCount = 0,
                )
            }, executor)
    }

    fun applyAsync(
        actor: RollbackActor,
        plan: RollbackPlan,
    ): CompletableFuture<RollbackExecutionResult> {
        val result =
            trackedResult<RollbackExecutionResult>()
                ?: return CompletableFuture.failedFuture(IllegalStateException("rollback service is closing"))
        beginApply(actor, plan, result)
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
    ) {
        if (!activeInstances.add(plan.instanceUuid)) {
            result.completeExceptionally(IllegalStateException("another rollback is already running in this instance"))
            return
        }
        val usesExternalState = plan.storageChanges.isNotEmpty() || plan.inventoryChanges.isNotEmpty()
        if (usesExternalState && !externalOperationActive.compareAndSet(false, true)) {
            activeInstances.remove(plan.instanceUuid)
            result.completeExceptionally(IllegalStateException("another storage or inventory operation is already running"))
            return
        }
        trackOperation(result, plan.instanceUuid, usesExternalState)
        val instance = MinecraftServer.getInstanceManager().getInstance(plan.instanceUuid)
        if (instance == null) {
            result.completeExceptionally(IllegalStateException("instance no longer exists"))
            return
        }

        Logger.rollback
            .hasRecoveryRequiredAsync(plan.instanceUuid, includeGlobalState = usesExternalState)
            .whenComplete { recoveryRequired, recoveryFailure ->
                when {
                    recoveryFailure != null -> {
                        result.completeExceptionally(recoveryFailure)
                    }

                    recoveryRequired == true -> {
                        result.completeExceptionally(
                            IllegalStateException("recovery acknowledgement is required before further operations"),
                        )
                    }

                    else -> {
                        loadChunks(instance, plan.blockChanges).whenComplete { _, loadFailure ->
                            if (loadFailure != null) {
                                result.completeExceptionally(loadFailure)
                            } else {
                                instance.scheduleNextTick {
                                    prepareAndPersist(actor, instance, plan, result)
                                }
                            }
                        }
                    }
                }
            }
    }

    fun undoAsync(actor: RollbackActor): CompletableFuture<RollbackExecutionResult> = replayLatest(actor, undo = true)

    fun redoAsync(actor: RollbackActor): CompletableFuture<RollbackExecutionResult> = replayLatest(actor, undo = false)

    fun acknowledgeRecoveryAsync(): CompletableFuture<Int> =
        synchronized(lifecycleLock) {
            if (closing.get()) return@synchronized CompletableFuture.failedFuture(IllegalStateException("rollback service is closing"))
            if (activeInstances.isNotEmpty() || externalOperationActive.get()) {
                return@synchronized CompletableFuture.failedFuture(IllegalStateException("wait for active operations to finish"))
            }
            Logger.rollback.acknowledgeRecoveryAsync()
        }

    private fun prepareAndPersist(
        actor: RollbackActor,
        instance: Instance,
        plan: RollbackPlan,
        result: CompletableFuture<RollbackExecutionResult>,
    ) {
        if (result.isDone) return
        val simulated = mutableMapOf<Triple<Int, Int, Int>, Block>()
        val simulatedInventory = mutableMapOf<Pair<UUID, Int>, net.minestom.server.item.ItemStack>()
        val simulatedEntities = mutableMapOf<UUID, Boolean>()
        val blockedPositions = mutableSetOf<Triple<Int, Int, Int>>()
        val changes = mutableListOf<RollbackChange>()
        var skipped = plan.skippedBlockCount

        try {
            for (candidate in plan.blockChanges) {
                val position = Triple(candidate.x, candidate.y, candidate.z)
                if (position in blockedPositions) {
                    skipped++
                    continue
                }
                val current = simulated[position] ?: instance.getBlock(candidate.x, candidate.y, candidate.z)
                val expected = block(candidate.expectedState, candidate.expectedMaterialKey, candidate.expectedNbt)
                val target = block(candidate.targetState, candidate.targetMaterialKey, candidate.targetNbt)
                if (target == null || (plan.safeMode && (expected == null || !sameBlock(current, expected)))) {
                    blockedPositions += position
                    skipped++
                    continue
                }
                changes +=
                    RollbackChange(
                        operationId = 0,
                        sequence = changes.size,
                        blockLogId = candidate.blockLogId,
                        changeKind = RollbackChangeKind.BLOCK,
                        x = candidate.x,
                        y = candidate.y,
                        z = candidate.z,
                        beforeBlockState = current.state(),
                        beforeBlockNbt = ItemCodec.encodeBlockNbt(current.nbt()),
                        afterBlockState = target.state(),
                        afterBlockNbt = ItemCodec.encodeBlockNbt(target.nbt()),
                    )
                simulated[position] = target
            }
            for (candidate in plan.storageChanges) {
                if (StorageRollbackAdapters.adapter(candidate.source) == null) {
                    skipped++
                    continue
                }
                changes +=
                    RollbackChange(
                        operationId = 0,
                        sequence = changes.size,
                        storageLogId = candidate.storageLogId,
                        changeKind = RollbackChangeKind.STORAGE,
                        storageSource = candidate.source,
                        storageId = candidate.storageId,
                        storageAction = candidate.targetAction,
                        itemData = ItemCodec.encodeItem(candidate.item),
                        amount = candidate.amount,
                        storageSlot = candidate.slot,
                    )
            }
            for (candidate in plan.inventoryChanges) {
                val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(candidate.playerUuid)
                if (player == null || candidate.slot !in 0 until player.inventory.size) {
                    if (candidate.inventoryLogId == null) {
                        error("snapshot target ${candidate.playerUuid} must be online with a valid inventory")
                    }
                    skipped++
                    continue
                }
                val key = candidate.playerUuid to candidate.slot
                val current = simulatedInventory[key] ?: player.inventory.getItemStack(candidate.slot)
                if (candidate.inventoryLogId == null && current == candidate.targetItem) continue
                if (plan.safeMode && candidate.expectedItem != null && current != candidate.expectedItem) {
                    skipped++
                    continue
                }
                changes +=
                    RollbackChange(
                        operationId = 0,
                        sequence = changes.size,
                        inventoryLogId = candidate.inventoryLogId,
                        changeKind = RollbackChangeKind.INVENTORY,
                        inventoryPlayerUuid = candidate.playerUuid,
                        inventorySlot = candidate.slot,
                        beforeItemData = ItemCodec.encodeItem(current),
                        afterItemData = ItemCodec.encodeItem(candidate.targetItem),
                    )
                simulatedInventory[key] = candidate.targetItem
            }
            for (candidate in plan.entityChanges) {
                val currentEntity = instance.getEntityByUuid(candidate.entityUuid)
                val currentExists = simulatedEntities[candidate.entityUuid] ?: (currentEntity != null)
                val expectedExists = candidate.targetAction == EntityChangeAction.DESPAWN
                if (plan.safeMode && currentExists != expectedExists) {
                    skipped++
                    continue
                }
                changes +=
                    RollbackChange(
                        operationId = 0,
                        sequence = changes.size,
                        entityLogId = candidate.entityLogId,
                        changeKind = RollbackChangeKind.ENTITY,
                        entityUuid = candidate.entityUuid,
                        entityType = currentEntity?.entityType?.key()?.asString() ?: candidate.entityType,
                        entityAction = candidate.targetAction,
                        entityPosition = currentEntity?.position ?: candidate.position,
                        entityVelocity = currentEntity?.velocity ?: candidate.velocity,
                        entityTagData = currentEntity?.let { ItemCodec.encodeBlockNbt(it.tagHandler().asCompound()) } ?: candidate.tagData,
                    )
                simulatedEntities[candidate.entityUuid] = candidate.targetAction == EntityChangeAction.SPAWN
            }
        } catch (exception: Exception) {
            result.completeExceptionally(exception)
            return
        }

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

        Logger.rollback.insertOperationAsync(operation, changes).whenComplete { operationId, persistFailure ->
            if (persistFailure != null) {
                result.completeExceptionally(persistFailure)
                return@whenComplete
            }
            operationIds[result] = operationId
            if (result.isDone) {
                Logger.rollback.updateStatusAsync(operationId, RollbackStatus.FAILED)
                return@whenComplete
            }
            Logger.rollback
                .startOperationAsync(
                    operationId,
                    RollbackStatus.PREPARED,
                    RollbackStatus.APPLYING,
                    changes,
                    expectedRolledBack = plan.kind == RollbackOperationKind.RESTORE,
                ).whenComplete { _, transitionFailure ->
                    if (transitionFailure != null) {
                        completeFailureAfterStatus(operationId, RollbackStatus.FAILED, transitionFailure, result)
                        return@whenComplete
                    }
                    if (result.isDone) {
                        Logger.rollback.updateStatusAsync(operationId, RollbackStatus.RECOVERY_REQUIRED)
                        return@whenComplete
                    }
                    instance.scheduleNextTick {
                        applyBatches(instance, operationId, plan, changes, skipped, result)
                    }
                }
        }
    }

    private fun applyBatches(
        instance: Instance,
        operationId: Long,
        plan: RollbackPlan,
        changes: List<RollbackChange>,
        initialSkipped: Int,
        result: CompletableFuture<RollbackExecutionResult>,
    ) {
        val blockChanges = changes.filter { it.changeKind == RollbackChangeKind.BLOCK }
        val storageChanges = changes.filter { it.changeKind == RollbackChangeKind.STORAGE }
        val inventoryChanges = changes.filter { it.changeKind == RollbackChangeKind.INVENTORY }
        val entityChanges = changes.filter { it.changeKind == RollbackChangeKind.ENTITY }
        val appliedBlocks = mutableListOf<RollbackChange>()
        val appliedStorage = mutableListOf<RollbackChange>()
        val blockedPositions = mutableSetOf<Triple<Int, Int, Int>>()
        var index = 0
        var skipped = initialSkipped

        fun nextBatch() {
            if (result.isDone) return
            try {
                val end = minOf(index + Logger.config.rollbackBatchSize, blockChanges.size)
                while (index < end) {
                    if (result.isDone) return
                    val change = blockChanges[index++]
                    val position = Triple(change.x!!, change.y!!, change.z!!)
                    if (position in blockedPositions) {
                        skipped++
                        continue
                    }
                    val current = instance.getBlock(change.x, change.y, change.z)
                    val before = block(change.beforeBlockState, null, change.beforeBlockNbt)
                    val after = block(change.afterBlockState, null, change.afterBlockNbt)
                    if (before == null || after == null || !sameBlock(current, before)) {
                        blockedPositions += position
                        skipped++
                        continue
                    }
                    if (
                        !VanillaStorage.applyBlockTransition(
                            instance,
                            change.x,
                            change.y,
                            change.z,
                            after,
                        )
                    ) {
                        blockedPositions += position
                        skipped++
                        continue
                    }
                    appliedBlocks += change
                }
            } catch (exception: Exception) {
                finishApplyFailure(
                    operationId,
                    exception,
                    compensateApplied(instance, appliedBlocks, emptyList(), emptyList(), emptyList(), reverseExternal = true),
                    result,
                )
                return
            }

            if (index < blockChanges.size) {
                instance.scheduleNextTick { nextBatch() }
                return
            }

            val remainingStorage = if (plan.kind == RollbackOperationKind.RESTORE) emptyList() else storageChanges
            applyStorage(remainingStorage, reverse = false, appliedStorage).whenComplete { _, storageFailure ->
                if (result.isDone) return@whenComplete
                if (storageFailure != null) {
                    finishApplyFailure(
                        operationId,
                        storageFailure,
                        compensateApplied(instance, appliedBlocks, appliedStorage, emptyList(), emptyList(), reverseExternal = true),
                        result,
                        forceRecovery = true,
                    )
                    return@whenComplete
                }

                val appliedInventory = mutableListOf<RollbackChange>()
                applyInventory(inventoryChanges, reverse = false, appliedInventory).whenComplete { _, inventoryFailure ->
                    if (result.isDone) return@whenComplete
                    if (inventoryFailure != null) {
                        finishApplyFailure(
                            operationId,
                            inventoryFailure,
                            compensateApplied(
                                instance,
                                appliedBlocks,
                                appliedStorage,
                                appliedInventory,
                                emptyList(),
                                reverseExternal = true,
                            ),
                            result,
                        )
                        return@whenComplete
                    }

                    val appliedEntities = mutableListOf<RollbackChange>()
                    applyEntities(instance, entityChanges, reverse = false, appliedEntities).whenComplete { _, entityFailure ->
                        if (result.isDone) return@whenComplete
                        if (entityFailure != null) {
                            finishApplyFailure(
                                operationId,
                                entityFailure,
                                compensateApplied(
                                    instance,
                                    appliedBlocks,
                                    appliedStorage,
                                    appliedInventory,
                                    appliedEntities,
                                    reverseExternal = true,
                                ),
                                result,
                            )
                            return@whenComplete
                        }

                        val applied = appliedBlocks + appliedStorage + appliedInventory + appliedEntities
                        val rolledBack = plan.kind == RollbackOperationKind.ROLLBACK
                        Logger.rollback
                            .completeOperationAsync(operationId, applied, rolledBack, skipped)
                            .whenComplete { _, failure ->
                                if (result.isDone) return@whenComplete
                                if (failure == null) {
                                    result.complete(RollbackExecutionResult(plan.kind, operationId, applied.size, skipped))
                                } else {
                                    val compensation =
                                        compensateApplied(
                                            instance,
                                            appliedBlocks,
                                            appliedStorage,
                                            appliedInventory,
                                            appliedEntities,
                                            reverseExternal = true,
                                        )
                                    finishApplyFailure(operationId, failure, compensation, result, forceRecovery = true)
                                }
                            }
                    }
                }
            }
        }

        if (plan.kind == RollbackOperationKind.RESTORE) {
            applyStorage(storageChanges, reverse = false, appliedStorage).whenComplete { _, storageFailure ->
                if (storageFailure != null) {
                    finishApplyFailure(
                        operationId,
                        storageFailure,
                        compensateApplied(instance, emptyList(), appliedStorage, emptyList(), emptyList(), reverseExternal = true),
                        result,
                        forceRecovery = true,
                    )
                } else {
                    nextBatch()
                }
            }
        } else {
            nextBatch()
        }
    }

    private fun replayLatest(
        actor: RollbackActor,
        undo: Boolean,
    ): CompletableFuture<RollbackExecutionResult> {
        val result =
            trackedResult<RollbackExecutionResult>()
                ?: return CompletableFuture.failedFuture(IllegalStateException("rollback service is closing"))
        Logger.rollback.findLatestOperationAsync(actor.uuid).whenComplete { operation, lookupFailure ->
            if (lookupFailure != null) {
                result.completeExceptionally(lookupFailure)
                return@whenComplete
            }
            val requiredStatus = if (undo) RollbackStatus.APPLIED else RollbackStatus.UNDONE
            if (operation == null || operation.status != requiredStatus || operation.kind == RollbackOperationKind.LEGACY) {
                result.completeExceptionally(IllegalStateException(if (undo) "nothing to undo" else "nothing to redo"))
                return@whenComplete
            }
            beginReplay(operation, requiredStatus, undo, result)
        }
        return result
    }

    private fun beginReplay(
        operation: RollbackOperation,
        requiredStatus: RollbackStatus,
        undo: Boolean,
        result: CompletableFuture<RollbackExecutionResult>,
    ) {
        if (!activeInstances.add(operation.instanceUuid)) {
            result.completeExceptionally(IllegalStateException("another rollback is already running in this instance"))
            return
        }
        val instance = MinecraftServer.getInstanceManager().getInstance(operation.instanceUuid)
        if (instance == null) {
            activeInstances.remove(operation.instanceUuid)
            result.completeExceptionally(IllegalStateException("instance no longer exists"))
            return
        }
        Logger.rollback.findChangesAsync(operation.id, appliedOnly = true).whenComplete { stored, changesFailure ->
            if (changesFailure != null) {
                activeInstances.remove(operation.instanceUuid)
                result.completeExceptionally(changesFailure)
                return@whenComplete
            }
            val usesExternalState =
                stored.any { it.changeKind == RollbackChangeKind.STORAGE || it.changeKind == RollbackChangeKind.INVENTORY }
            if (usesExternalState && !externalOperationActive.compareAndSet(false, true)) {
                activeInstances.remove(operation.instanceUuid)
                result.completeExceptionally(IllegalStateException("another storage or inventory operation is already running"))
                return@whenComplete
            }
            operationIds[result] = operation.id
            trackOperation(result, operation.instanceUuid, usesExternalState)
            val ordered = if (undo) stored.asReversed() else stored
            Logger.rollback
                .hasRecoveryRequiredAsync(operation.instanceUuid, includeGlobalState = usesExternalState)
                .whenComplete { recoveryRequired, recoveryFailure ->
                    when {
                        recoveryFailure != null -> {
                            result.completeExceptionally(recoveryFailure)
                        }

                        recoveryRequired == true -> {
                            result.completeExceptionally(
                                IllegalStateException("recovery acknowledgement is required before further operations"),
                            )
                        }

                        else -> {
                            loadChunksFromChanges(instance, ordered).whenComplete { _, loadFailure ->
                                if (loadFailure != null) {
                                    result.completeExceptionally(loadFailure)
                                    return@whenComplete
                                }
                                val transition = if (undo) RollbackStatus.UNDOING else RollbackStatus.REDOING
                                val targetRolledBack =
                                    if (undo) {
                                        operation.kind == RollbackOperationKind.RESTORE
                                    } else {
                                        operation.kind == RollbackOperationKind.ROLLBACK
                                    }
                                Logger.rollback
                                    .startOperationAsync(
                                        operation.id,
                                        requiredStatus,
                                        transition,
                                        ordered,
                                        expectedRolledBack = !targetRolledBack,
                                    ).whenComplete { _, transitionFailure ->
                                        if (transitionFailure != null) {
                                            result.completeExceptionally(transitionFailure)
                                            return@whenComplete
                                        }
                                        instance.scheduleNextTick {
                                            replayBatches(instance, operation, ordered, undo, result)
                                        }
                                    }
                            }
                        }
                    }
                }
        }
    }

    private fun replayBatches(
        instance: Instance,
        operation: RollbackOperation,
        changes: List<RollbackChange>,
        undo: Boolean,
        result: CompletableFuture<RollbackExecutionResult>,
    ) {
        val blockChanges = changes.filter { it.changeKind == RollbackChangeKind.BLOCK }
        val storageChanges = changes.filter { it.changeKind == RollbackChangeKind.STORAGE }
        val inventoryChanges = changes.filter { it.changeKind == RollbackChangeKind.INVENTORY }
        val entityChanges = changes.filter { it.changeKind == RollbackChangeKind.ENTITY }
        val appliedBlocks = mutableListOf<RollbackChange>()
        val appliedStorage = mutableListOf<RollbackChange>()
        val storageBeforeBlocks =
            (undo && operation.kind == RollbackOperationKind.ROLLBACK) ||
                (!undo && operation.kind == RollbackOperationKind.RESTORE)
        var index = 0

        fun nextBatch() {
            if (result.isDone) return
            try {
                val end = minOf(index + Logger.config.rollbackBatchSize, blockChanges.size)
                while (index < end) {
                    if (result.isDone) return
                    val change = blockChanges[index++]
                    val x = change.x ?: continue
                    val y = change.y ?: continue
                    val z = change.z ?: continue
                    val expectedState = if (undo) change.afterBlockState else change.beforeBlockState
                    val expectedNbt = if (undo) change.afterBlockNbt else change.beforeBlockNbt
                    val targetState = if (undo) change.beforeBlockState else change.afterBlockState
                    val targetNbt = if (undo) change.beforeBlockNbt else change.afterBlockNbt
                    val expected = block(expectedState, null, expectedNbt)
                    val target = block(targetState, null, targetNbt)
                    if (expected == null || target == null || !sameBlock(instance.getBlock(x, y, z), expected)) {
                        throw IllegalStateException("block at $x,$y,$z changed after the operation")
                    }
                    check(
                        VanillaStorage.applyBlockTransition(
                            instance,
                            x,
                            y,
                            z,
                            target,
                        ),
                    ) { "block transition at $x,$y,$z was rejected" }
                    appliedBlocks += change
                }
            } catch (exception: Exception) {
                finishReplayFailure(
                    operation.id,
                    undo,
                    exception,
                    compensateApplied(
                        instance,
                        appliedBlocks,
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        reverseExternal = !undo,
                        replayUndo = undo,
                    ),
                    result,
                )
                return
            }

            if (index < blockChanges.size) {
                instance.scheduleNextTick { nextBatch() }
                return
            }

            val remainingStorage = if (storageBeforeBlocks) emptyList() else storageChanges
            applyStorage(remainingStorage, reverse = undo, appliedStorage).whenComplete { _, storageFailure ->
                if (result.isDone) return@whenComplete
                if (storageFailure != null) {
                    finishReplayFailure(
                        operation.id,
                        undo,
                        storageFailure,
                        compensateApplied(
                            instance,
                            appliedBlocks,
                            appliedStorage,
                            emptyList(),
                            emptyList(),
                            reverseExternal = !undo,
                            replayUndo = undo,
                        ),
                        result,
                        forceRecovery = true,
                    )
                    return@whenComplete
                }

                val appliedInventory = mutableListOf<RollbackChange>()
                applyInventory(inventoryChanges, reverse = undo, appliedInventory).whenComplete { _, inventoryFailure ->
                    if (result.isDone) return@whenComplete
                    if (inventoryFailure != null) {
                        finishReplayFailure(
                            operation.id,
                            undo,
                            inventoryFailure,
                            compensateApplied(
                                instance,
                                appliedBlocks,
                                appliedStorage,
                                appliedInventory,
                                emptyList(),
                                reverseExternal = !undo,
                                replayUndo = undo,
                            ),
                            result,
                        )
                        return@whenComplete
                    }

                    val appliedEntities = mutableListOf<RollbackChange>()
                    applyEntities(instance, entityChanges, reverse = undo, appliedEntities).whenComplete { _, entityFailure ->
                        if (result.isDone) return@whenComplete
                        if (entityFailure != null) {
                            finishReplayFailure(
                                operation.id,
                                undo,
                                entityFailure,
                                compensateApplied(
                                    instance,
                                    appliedBlocks,
                                    appliedStorage,
                                    appliedInventory,
                                    appliedEntities,
                                    reverseExternal = !undo,
                                    replayUndo = undo,
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
                                operation.kind ==
                                    RollbackOperationKind.ROLLBACK
                            }
                        Logger.rollback
                            .completeReplayAsync(operation, transition, targetStatus, targetRolledBack)
                            .whenComplete { _, failure ->
                                if (result.isDone) return@whenComplete
                                if (failure == null) {
                                    result.complete(
                                        RollbackExecutionResult(
                                            operation.kind,
                                            operation.id,
                                            appliedBlocks.size + appliedStorage.size + appliedInventory.size + appliedEntities.size,
                                            0,
                                        ),
                                    )
                                } else {
                                    val compensation =
                                        compensateApplied(
                                            instance,
                                            appliedBlocks,
                                            appliedStorage,
                                            appliedInventory,
                                            appliedEntities,
                                            reverseExternal = !undo,
                                            replayUndo = undo,
                                        )
                                    finishReplayFailure(operation.id, undo, failure, compensation, result, forceRecovery = true)
                                }
                            }
                    }
                }
            }
        }

        if (storageBeforeBlocks) {
            applyStorage(storageChanges, reverse = undo, appliedStorage).whenComplete { _, storageFailure ->
                if (storageFailure != null) {
                    finishReplayFailure(
                        operation.id,
                        undo,
                        storageFailure,
                        compensateApplied(
                            instance,
                            emptyList(),
                            appliedStorage,
                            emptyList(),
                            emptyList(),
                            reverseExternal = !undo,
                            replayUndo = undo,
                        ),
                        result,
                        forceRecovery = true,
                    )
                } else {
                    nextBatch()
                }
            }
        } else {
            nextBatch()
        }
    }

    private fun compensateApplied(
        instance: Instance,
        blockChanges: List<RollbackChange>,
        storageChanges: List<RollbackChange>,
        inventoryChanges: List<RollbackChange>,
        entityChanges: List<RollbackChange>,
        reverseExternal: Boolean,
        replayUndo: Boolean? = null,
    ): CompletableFuture<Void> {
        val failures = mutableListOf<Throwable>()
        var chain = CompletableFuture.completedFuture<Void>(null)
        chain =
            chain.thenCompose {
                captureCompensation(
                    applyEntities(instance, entityChanges.asReversed(), reverseExternal, mutableListOf()),
                    failures,
                )
            }
        chain =
            chain.thenCompose {
                captureCompensation(
                    applyInventory(inventoryChanges.asReversed(), reverseExternal, mutableListOf()),
                    failures,
                )
            }
        chain =
            chain.thenCompose {
                captureCompensation(
                    applyStorage(storageChanges.asReversed(), reverseExternal, mutableListOf()),
                    failures,
                )
            }
        chain =
            chain.thenCompose {
                val blocksDone = CompletableFuture<Void>()
                instance.scheduleNextTick {
                    try {
                        for (change in blockChanges.asReversed()) {
                            val x = change.x ?: error("compensation block x is missing")
                            val y = change.y ?: error("compensation block y is missing")
                            val z = change.z ?: error("compensation block z is missing")
                            val state = if (replayUndo == true) change.afterBlockState else change.beforeBlockState
                            val nbt = if (replayUndo == true) change.afterBlockNbt else change.beforeBlockNbt
                            val target = block(state, null, nbt) ?: error("compensation block state is missing")
                            val expectedState = if (replayUndo == true) change.beforeBlockState else change.afterBlockState
                            val expectedNbt = if (replayUndo == true) change.beforeBlockNbt else change.afterBlockNbt
                            val expected = block(expectedState, null, expectedNbt) ?: error("applied block state is missing")
                            check(sameBlock(instance.getBlock(x, y, z), expected)) {
                                "block changed while compensating"
                            }
                            check(
                                VanillaStorage.applyBlockTransition(
                                    instance,
                                    x,
                                    y,
                                    z,
                                    target,
                                ),
                            ) { "block compensation at $x,$y,$z was rejected" }
                        }
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                    blocksDone.complete(null)
                }
                blocksDone
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

    private fun finishApplyFailure(
        operationId: Long,
        failure: Throwable,
        compensation: CompletableFuture<Void>,
        result: CompletableFuture<RollbackExecutionResult>,
        forceRecovery: Boolean = false,
    ) {
        compensation.whenComplete { _, compensationFailure ->
            if (compensationFailure != null) failure.addSuppressed(compensationFailure)
            val status =
                if (forceRecovery || compensationFailure != null) RollbackStatus.RECOVERY_REQUIRED else RollbackStatus.FAILED
            completeFailureAfterStatus(operationId, status, failure, result)
        }
    }

    private fun finishReplayFailure(
        operationId: Long,
        undo: Boolean,
        failure: Throwable,
        compensation: CompletableFuture<Void>,
        result: CompletableFuture<RollbackExecutionResult>,
        forceRecovery: Boolean = false,
    ) {
        compensation.whenComplete { _, compensationFailure ->
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

    private fun completeFailureAfterStatus(
        operationId: Long,
        status: RollbackStatus,
        failure: Throwable,
        result: CompletableFuture<RollbackExecutionResult>,
    ) {
        Logger.rollback.updateStatusAsync(operationId, status).whenComplete { _, statusFailure ->
            if (statusFailure != null) failure.addSuppressed(statusFailure)
            result.completeExceptionally(failure)
        }
    }

    private fun applyStorage(
        changes: List<RollbackChange>,
        reverse: Boolean,
        applied: MutableList<RollbackChange>,
    ): CompletableFuture<Void> {
        var chain = CompletableFuture.completedFuture<Void>(null)
        for (change in changes) {
            chain =
                chain.thenCompose {
                    if (closing.get()) {
                        return@thenCompose CompletableFuture.failedFuture(
                            IllegalStateException("rollback service is closing"),
                        )
                    }
                    val source =
                        change.storageSource
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("storage source is missing"))
                    val storageId =
                        change.storageId
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("storage ID is missing"))
                    val action =
                        change.storageAction
                            ?: return@thenCompose CompletableFuture.failedFuture(
                                IllegalStateException("storage action is missing"),
                            )
                    val itemData =
                        change.itemData
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("storage item is missing"))
                    val amount =
                        change.amount
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("storage amount is missing"))
                    val adapter =
                        StorageRollbackAdapters.adapter(source)
                            ?: return@thenCompose CompletableFuture.failedFuture(
                                IllegalStateException("no rollback adapter for storage source '$source'"),
                            )
                    val effectiveAction = if (reverse) action.inverse() else action
                    adapter
                        .apply(
                            storageId,
                            change.storageSlot,
                            ItemCodec.decodeItem(itemData),
                            amount,
                            effectiveAction,
                        ).thenApply { success ->
                            check(success) { "storage '$storageId' rejected $effectiveAction" }
                            applied += change
                            null
                        }
                }
        }
        return chain
    }

    private fun applyInventory(
        changes: List<RollbackChange>,
        reverse: Boolean,
        applied: MutableList<RollbackChange>,
    ): CompletableFuture<Void> {
        val writes =
            changes.map { change ->
                val playerUuid = change.inventoryPlayerUuid ?: error("inventory player is missing")
                val slot = change.inventorySlot ?: error("inventory slot is missing")
                val beforeData = if (reverse) change.afterItemData else change.beforeItemData
                val afterData = if (reverse) change.beforeItemData else change.afterItemData
                InventoryWrite(
                    change = change,
                    slot = slot,
                    before = beforeData?.let(ItemCodec::decodeItem) ?: error("inventory before item is missing"),
                    after = afterData?.let(ItemCodec::decodeItem) ?: error("inventory after item is missing"),
                ) to playerUuid
            }
        var chain = CompletableFuture.completedFuture<Void>(null)
        for ((playerUuid, playerWrites) in writes.groupBy({ it.second }, { it.first })) {
            chain =
                chain.thenCompose {
                    if (closing.get()) {
                        return@thenCompose CompletableFuture.failedFuture(
                            IllegalStateException("rollback service is closing"),
                        )
                    }
                    val player =
                        MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(playerUuid)
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("player $playerUuid must be online"))
                    val future = CompletableFuture<Void>()
                    player.scheduleNextTick {
                        try {
                            val simulated = mutableMapOf<Int, net.minestom.server.item.ItemStack>()
                            for (write in playerWrites) {
                                val current = simulated[write.slot] ?: player.inventory.getItemStack(write.slot)
                                check(current == write.before) { "inventory slot ${write.slot} changed after preview" }
                                simulated[write.slot] = write.after
                            }
                            RollbackMutationGuard.suppressInventory(playerUuid) {
                                for (write in playerWrites) player.inventory.setItemStack(write.slot, write.after)
                            }
                            applied += playerWrites.map(InventoryWrite::change)
                            future.complete(null)
                        } catch (exception: Exception) {
                            future.completeExceptionally(exception)
                        }
                    }
                    future
                }
        }
        return chain
    }

    private fun applyEntities(
        instance: Instance,
        changes: List<RollbackChange>,
        reverse: Boolean,
        applied: MutableList<RollbackChange>,
    ): CompletableFuture<Void> {
        var chain = CompletableFuture.completedFuture<Void>(null)
        for (change in changes) {
            chain =
                chain.thenCompose {
                    if (closing.get()) {
                        return@thenCompose CompletableFuture.failedFuture(
                            IllegalStateException("rollback service is closing"),
                        )
                    }
                    val entityUuid =
                        change.entityUuid
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity UUID is missing"))
                    val action =
                        change.entityAction
                            ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity action is missing"))
                    val effectiveAction = if (reverse) action.inverse() else action
                    if (effectiveAction == EntityChangeAction.DESPAWN) {
                        val entity =
                            instance.getEntityByUuid(entityUuid)
                                ?: return@thenCompose CompletableFuture.failedFuture(
                                    IllegalStateException("entity $entityUuid no longer exists"),
                                )
                        RollbackMutationGuard.suppressEntity(entityUuid) { entity.remove() }
                        applied += change
                        CompletableFuture.completedFuture(null)
                    } else {
                        if (instance.getEntityByUuid(entityUuid) != null) {
                            return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity $entityUuid already exists"))
                        }
                        val typeKey =
                            change.entityType
                                ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity type is missing"))
                        val type =
                            net.minestom.server.entity.EntityType
                                .fromKey(typeKey)
                                ?: return@thenCompose CompletableFuture.failedFuture(
                                    IllegalStateException("unknown entity type '$typeKey'"),
                                )
                        val position =
                            change.entityPosition
                                ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("entity position is missing"))
                        val entity =
                            net.minestom.server.entity
                                .Entity(type, entityUuid)
                        change.entityVelocity?.let(entity::setVelocity)
                        ItemCodec.decodeBlockNbt(change.entityTagData)?.let { entity.tagHandler().updateContent(it) }
                        RollbackMutationGuard.beginEntity(entityUuid)
                        entity
                            .setInstance(instance, position)
                            .whenComplete {
                                _,
                                _,
                                ->
                                RollbackMutationGuard.endEntity(entityUuid)
                            }.thenApply {
                                applied += change
                                null
                            }
                    }
                }
        }
        return chain
    }

    private fun loadChunks(
        instance: Instance,
        changes: List<BlockChangePlan>,
    ): CompletableFuture<Void> {
        val chunks = changes.map { (it.x shr 4) to (it.z shr 4) }.toSet()
        return CompletableFuture.allOf(*chunks.map { (x, z) -> instance.loadChunk(x, z) }.toTypedArray())
    }

    private fun loadChunksFromChanges(
        instance: Instance,
        changes: List<RollbackChange>,
    ): CompletableFuture<Void> {
        val chunks = changes.mapNotNull { change -> change.x?.let { x -> change.z?.let { z -> (x shr 4) to (z shr 4) } } }.toSet()
        return CompletableFuture.allOf(*chunks.map { (x, z) -> instance.loadChunk(x, z) }.toTypedArray())
    }

    private fun block(
        state: String?,
        materialKey: String?,
        nbt: ByteArray?,
    ): Block? {
        val base = state?.let(Block::fromState) ?: materialKey?.let(Block::fromKey) ?: return null
        return base.withNbt(ItemCodec.decodeBlockNbt(nbt))
    }

    private fun sameBlock(
        first: Block,
        second: Block,
    ): Boolean = first.state() == second.state() && first.nbt() == second.nbt()

    private fun trackOperation(
        result: CompletableFuture<*>,
        instanceUuid: UUID,
        usesExternalState: Boolean,
    ) {
        result.whenComplete { _, _ ->
            activeInstances.remove(instanceUuid)
            if (usesExternalState) externalOperationActive.compareAndSet(true, false)
        }
    }

    private fun <T> trackedResult(): CompletableFuture<T>? =
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
        synchronized(lifecycleLock) { closing.set(true) }
        val active = activeResults.toTypedArray()
        var recoveryFailure: Exception? = null
        if (active.isNotEmpty()) {
            runCatching {
                CompletableFuture
                    .allOf(*active)
                    .handle { _, _ -> null }
                    .get(5, TimeUnit.SECONDS)
            }
            val remaining = active.filterNot { it.isDone }
            val interruptedIds = remaining.mapNotNull(operationIds::get).distinct()
            for (operationId in interruptedIds) {
                try {
                    Logger.rollback
                        .markRecoveryRequiredAsync(operationId)
                        .get(5, TimeUnit.SECONDS)
                } catch (exception: Exception) {
                    if (recoveryFailure == null) recoveryFailure = exception else recoveryFailure.addSuppressed(exception)
                }
            }
            remaining.forEach {
                it.completeExceptionally(IllegalStateException("rollback interrupted by shutdown"))
            }
        }
        executor.close()
        recoveryFailure?.let { throw it }
    }
}

private fun StorageChangeAction.inverse(): StorageChangeAction =
    when (this) {
        StorageChangeAction.DEPOSIT -> StorageChangeAction.WITHDRAW
        StorageChangeAction.WITHDRAW -> StorageChangeAction.DEPOSIT
    }

private fun EntityChangeAction.inverse(): EntityChangeAction =
    when (this) {
        EntityChangeAction.SPAWN -> EntityChangeAction.DESPAWN
        EntityChangeAction.DESPAWN -> EntityChangeAction.SPAWN
    }
