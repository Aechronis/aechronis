package net.aechronis.logger.objects

import net.minestom.server.item.ItemStack
import java.util.UUID

enum class RollbackOperationKind(
    val value: String,
) {
    ROLLBACK("rollback"),
    RESTORE("restore"),
    SNAPSHOT("snapshot"),
    LEGACY("legacy"),
    ;

    companion object {
        fun fromValue(value: String): RollbackOperationKind = entries.first { it.value == value }
    }
}

data class BlockChangePlan(
    val blockLogId: Long,
    val x: Int,
    val y: Int,
    val z: Int,
    val expectedState: String?,
    val expectedMaterialKey: String,
    val expectedNbt: ByteArray?,
    val targetState: String?,
    val targetMaterialKey: String,
    val targetNbt: ByteArray?,
)

enum class RollbackDomain {
    BLOCK,
    STORAGE,
    INVENTORY,
    ENTITY,
}

data class RollbackSelection(
    val domains: Set<RollbackDomain> = setOf(RollbackDomain.BLOCK),
    val storageActions: Set<StorageChangeAction>? = null,
    val inventoryActions: Set<InventoryChangeAction>? = null,
    val entityActions: Set<EntityChangeAction>? = null,
)

data class StorageChangePlan(
    val storageLogId: Long,
    val source: String,
    val storageId: String,
    val slot: Int?,
    val targetAction: StorageChangeAction,
    val item: ItemStack,
    val amount: Int,
)

data class InventoryChangePlan(
    val inventoryLogId: Long?,
    val playerUuid: UUID,
    val slot: Int,
    val expectedItem: ItemStack?,
    val targetItem: ItemStack,
)

data class EntityChangePlan(
    val entityLogId: Long,
    val entityUuid: UUID,
    val entityType: String,
    val targetAction: EntityChangeAction,
    val position: net.minestom.server.coordinate.Pos,
    val velocity: net.minestom.server.coordinate.Vec,
    val tagData: ByteArray?,
)

data class RollbackPlan(
    val kind: RollbackOperationKind,
    val instanceUuid: UUID,
    val targetTs: Long,
    val queryDesc: String,
    val safeMode: Boolean,
    val blockChanges: List<BlockChangePlan>,
    val storageChanges: List<StorageChangePlan> = emptyList(),
    val inventoryChanges: List<InventoryChangePlan> = emptyList(),
    val entityChanges: List<EntityChangePlan> = emptyList(),
    val skippedBlockCount: Int,
) {
    val totalChangeCount: Int get() = blockChanges.size + storageChanges.size + inventoryChanges.size + entityChanges.size
}

enum class RollbackStatus(
    val value: String,
) {
    PREPARED("prepared"),
    APPLYING("applying"),
    APPLIED("applied"),
    UNDOING("undoing"),
    UNDONE("undone"),
    REDOING("redoing"),
    FAILED("failed"),
    RECOVERY_REQUIRED("recovery_required"),
    ;

    companion object {
        fun fromValue(value: String): RollbackStatus = entries.first { it.value == value }
    }
}

enum class RollbackChangeKind(
    val value: String,
) {
    BLOCK("block"),
    STORAGE("storage"),
    INVENTORY("inventory"),
    ENTITY("entity"),
    ;

    companion object {
        fun fromValue(value: String): RollbackChangeKind = entries.first { it.value == value }
    }
}

data class RollbackOperation(
    val timestamp: Long,
    val actorUuid: UUID,
    val actorName: String,
    val instanceUuid: UUID,
    val kind: RollbackOperationKind,
    val queryDesc: String,
    val targetTs: Long,
    val safeMode: Boolean,
    val status: RollbackStatus,
    val blockChangeCount: Int,
    val skippedChangeCount: Int = 0,
    val completedTimestamp: Long? = null,
    val id: Long = 0,
)

data class RollbackChange(
    val operationId: Long,
    val sequence: Int,
    val changeKind: RollbackChangeKind,
    val id: Long = 0,
    val blockLogId: Long? = null,
    val storageLogId: Long? = null,
    val inventoryLogId: Long? = null,
    val entityLogId: Long? = null,
    val applied: Boolean = false,
    val x: Int? = null,
    val y: Int? = null,
    val z: Int? = null,
    val beforeBlockState: String? = null,
    val beforeBlockNbt: ByteArray? = null,
    val afterBlockState: String? = null,
    val afterBlockNbt: ByteArray? = null,
    val storageSource: String? = null,
    val storageId: String? = null,
    val storageAction: StorageChangeAction? = null,
    val itemData: ByteArray? = null,
    val amount: Int? = null,
    val storageSlot: Int? = null,
    val inventoryPlayerUuid: UUID? = null,
    val inventorySlot: Int? = null,
    val beforeItemData: ByteArray? = null,
    val afterItemData: ByteArray? = null,
    val entityUuid: UUID? = null,
    val entityType: String? = null,
    val entityAction: EntityChangeAction? = null,
    val entityPosition: net.minestom.server.coordinate.Pos? = null,
    val entityVelocity: net.minestom.server.coordinate.Vec? = null,
    val entityTagData: ByteArray? = null,
)
