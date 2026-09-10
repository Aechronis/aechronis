package net.aechronis.logger.objects

import net.aechronis.logger.Logger
import net.aechronis.logger.params.LookupParams
import net.minestom.server.coordinate.Pos
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/** Queries selected history domains and converts their recorded changes into an execution plan. */
internal class RollbackPlanner(
    private val executor: ExecutorService,
) {
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

                    RollbackOperationKind.CHUNK_RESTORE -> {
                        error("unreachable")
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
                                blockLogId = row.id,
                                timestamp = row.timestamp,
                                x = row.x,
                                y = row.y,
                                z = row.z,
                                expectedState = row.blockNewState,
                                expectedMaterialKey = row.blockNew,
                                expectedNbt = row.blockNewNbt,
                                targetState = row.blockOldState,
                                targetMaterialKey = row.blockOld,
                                targetNbt = row.blockOldNbt,
                            )
                        } else {
                            BlockChangePlan(
                                blockLogId = row.id,
                                timestamp = row.timestamp,
                                x = row.x,
                                y = row.y,
                                z = row.z,
                                expectedState = row.blockOldState,
                                expectedMaterialKey = row.blockOld,
                                expectedNbt = row.blockOldNbt,
                                targetState = row.blockNewState,
                                targetMaterialKey = row.blockNew,
                                targetNbt = row.blockNewNbt,
                            )
                        }
                    }
                val selectedStorage = storageRows
                val storageChanges =
                    selectedStorage.map { row ->
                        StorageChangePlan(
                            storageLogId = row.id,
                            timestamp = row.timestamp,
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
}
