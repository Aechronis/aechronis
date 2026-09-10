package net.aechronis.logger.objects

import net.aechronis.logger.utils.EntityStateCodec
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.LogMetadata
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import java.util.UUID

private sealed interface WorldChangeCandidate {
    val timestamp: Long
    val historyId: Long
}

private data class BlockCandidate(
    val plan: BlockChangePlan,
) : WorldChangeCandidate {
    override val timestamp: Long = plan.timestamp
    override val historyId: Long = plan.blockLogId ?: 0
}

private data class StorageCandidate(
    val plan: StorageChangePlan,
) : WorldChangeCandidate {
    override val timestamp: Long = plan.timestamp
    override val historyId: Long = plan.storageLogId
}

private data class PreparedWorldCandidate(
    val position: RollbackBlockPosition?,
    val change: RollbackChange?,
)

internal data class PreparedRollbackChanges(
    val changes: List<RollbackChange>,
    val skippedCount: Int,
)

/** Builds the exact ordered changes to persist without applying them. */
internal class RollbackPreparation {
    /** Captures and simulates live values at the caller's tick boundary before persistence. */
    fun prepare(
        instance: Instance,
        plan: RollbackPlan,
        preparedChunkRestore: PreparedRollbackChanges? = null,
    ): PreparedRollbackChanges {
        val simulatedInventory = mutableMapOf<Pair<UUID, Int>, net.minestom.server.item.ItemStack>()
        val simulatedEntities = mutableMapOf<UUID, Boolean>()
        val changes = mutableListOf<RollbackChange>()
        var skipped = plan.skippedBlockCount

        val worldPreparation = preparedChunkRestore ?: prepareWorldChanges(instance, plan)
        changes += worldPreparation.changes
        skipped += worldPreparation.skippedCount
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
                    entityTagData = currentEntity?.let(EntityStateCodec::encode) ?: candidate.tagData,
                )
            simulatedEntities[candidate.entityUuid] = candidate.targetAction == EntityChangeAction.SPAWN
        }
        return PreparedRollbackChanges(changes, skipped)
    }

    private fun prepareWorldChanges(
        instance: Instance,
        plan: RollbackPlan,
    ): PreparedRollbackChanges {
        val chronological =
            (
                plan.blockChanges.map(::BlockCandidate) +
                    plan.storageChanges.map(::StorageCandidate)
            ).sortedWith(
                compareBy<WorldChangeCandidate> { it.timestamp }
                    .thenBy { if (it is BlockCandidate) 0 else 1 }
                    .thenBy { it.historyId },
            )
        val ordered =
            if (plan.kind == RollbackOperationKind.ROLLBACK) {
                chronological.asReversed()
            } else {
                chronological
            }
        val blockPositions = plan.blockChanges.map { RollbackBlockPosition(it.x, it.y, it.z) }.toSet()
        val linkedPositions =
            plan.storageChanges
                .filter { it.source.equals(LogMetadata.VANILLA, ignoreCase = true) }
                .mapNotNull { change ->
                    val location = VanillaStorage.parseStorageId(change.storageId) ?: return@mapNotNull null
                    if (location.first != plan.instanceUuid) return@mapNotNull null
                    RollbackBlockPosition(location.second, location.third, location.fourth)
                }.toSet()
                .intersect(blockPositions)
        val simulated = mutableMapOf<RollbackBlockPosition, Block>()
        val invalidPositions = mutableSetOf<RollbackBlockPosition>()
        val prepared = mutableListOf<PreparedWorldCandidate>()

        for (candidate in ordered) {
            when (candidate) {
                is BlockCandidate -> {
                    val value = candidate.plan
                    val position = RollbackBlockPosition(value.x, value.y, value.z)
                    if (position in invalidPositions) {
                        prepared += PreparedWorldCandidate(position, null)
                        continue
                    }
                    val current = simulated[position] ?: instance.getBlock(position.x, position.y, position.z)
                    val expected =
                        RollbackBlocks.decode(
                            value.expectedState,
                            value.expectedMaterialKey,
                            value.expectedNbt,
                            value.expectedHandlerKey,
                        )
                    val target =
                        RollbackBlocks.decode(
                            value.targetState,
                            value.targetMaterialKey,
                            value.targetNbt,
                            value.targetHandlerKey,
                        )
                    if (
                        target == null ||
                        (plan.safeMode && (expected == null || !RollbackBlocks.matches(current, expected, value.expectedHandlerKey)))
                    ) {
                        invalidPositions += position
                        prepared += PreparedWorldCandidate(position, null)
                        continue
                    }
                    prepared +=
                        PreparedWorldCandidate(
                            position,
                            RollbackChange(
                                operationId = 0,
                                sequence = 0,
                                blockLogId = value.blockLogId,
                                changeKind = RollbackChangeKind.BLOCK,
                                x = value.x,
                                y = value.y,
                                z = value.z,
                                beforeBlockState = current.state(),
                                beforeBlockNbt = ItemCodec.encodeBlockNbt(current.nbt()),
                                beforeBlockHandler = current.handler()?.key?.asString(),
                                afterBlockState = target.state(),
                                afterBlockNbt = ItemCodec.encodeBlockNbt(target.nbt()),
                                afterBlockHandler = target.handler()?.key?.asString(),
                            ),
                        )
                    simulated[position] = target
                }

                is StorageCandidate -> {
                    val value = candidate.plan
                    val isVanilla = value.source.equals(LogMetadata.VANILLA, ignoreCase = true)
                    val adapter = StorageRollbackAdapters.adapter(value.source)
                    val location =
                        if (isVanilla) {
                            VanillaStorage.parseStorageId(value.storageId)
                        } else {
                            null
                        }
                    val position =
                        location
                            ?.takeIf { it.first == plan.instanceUuid }
                            ?.let { RollbackBlockPosition(it.second, it.third, it.fourth) }
                    if (position != null && position in invalidPositions) {
                        prepared += PreparedWorldCandidate(position, null)
                        continue
                    }
                    if (adapter == null) {
                        if (position != null && position in linkedPositions) invalidPositions += position
                        prepared += PreparedWorldCandidate(position, null)
                        continue
                    }
                    if (isVanilla && location == null) {
                        prepared += PreparedWorldCandidate(null, null)
                        continue
                    }

                    if (position != null) {
                        val current =
                            simulated[position]
                                ?: VanillaStorage.snapshotBlock(instance, position.x, position.y, position.z)
                        val projected =
                            VanillaStorage.projectBlock(
                                current,
                                value.slot,
                                value.item,
                                value.amount,
                                value.targetAction,
                            )
                        if (projected == null) {
                            invalidPositions += position
                            prepared += PreparedWorldCandidate(position, null)
                            continue
                        }
                        simulated[position] = projected
                    }

                    prepared +=
                        PreparedWorldCandidate(
                            position,
                            RollbackChange(
                                operationId = 0,
                                sequence = 0,
                                storageLogId = value.storageLogId,
                                changeKind = RollbackChangeKind.STORAGE,
                                storageSource = value.source,
                                storageId = value.storageId,
                                storageAction = value.targetAction,
                                itemData = ItemCodec.encodeItem(value.item),
                                amount = value.amount,
                                storageSlot = value.slot,
                            ),
                        )
                }
            }
        }

        val changes =
            prepared
                .filter {
                    it.change != null &&
                        (it.position == null || it.position !in invalidPositions || it.position !in linkedPositions)
                }.mapIndexed { sequence, candidate -> requireNotNull(candidate.change).copy(sequence = sequence) }
        return PreparedRollbackChanges(
            changes = changes,
            skippedCount = prepared.size - changes.size,
        )
    }

    fun prepareChunkRestoreChanges(plan: RollbackPlan): PreparedRollbackChanges =
        PreparedRollbackChanges(
            changes =
                plan.blockChanges.mapIndexed { sequence, change ->
                    RollbackChange(
                        operationId = 0,
                        sequence = sequence,
                        blockLogId = change.blockLogId,
                        changeKind = RollbackChangeKind.BLOCK,
                        x = change.x,
                        y = change.y,
                        z = change.z,
                        beforeBlockState = change.expectedState,
                        beforeBlockNbt = change.expectedNbt,
                        beforeBlockHandler = change.expectedHandlerKey,
                        afterBlockState = change.targetState,
                        afterBlockNbt = change.targetNbt,
                        afterBlockHandler = change.targetHandlerKey,
                    )
                },
            skippedCount = 0,
        )
}
