package net.aechronis.logger.objects

import java.util.UUID

data class BlockChangePlan(
    val x: Int,
    val y: Int,
    val z: Int,
    val restoreState: String?,
    val restoreMaterialKey: String,
    val restoreNbt: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BlockChangePlan

        if (x != other.x) return false
        if (y != other.y) return false
        if (z != other.z) return false
        if (restoreState != other.restoreState) return false
        if (restoreMaterialKey != other.restoreMaterialKey) return false
        if (!restoreNbt.contentEquals(other.restoreNbt)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        result = 31 * result + z
        result = 31 * result + (restoreState?.hashCode() ?: 0)
        result = 31 * result + restoreMaterialKey.hashCode()
        result = 31 * result + (restoreNbt?.contentHashCode() ?: 0)
        return result
    }
}

data class RollbackPlan(
    val instanceUuid: UUID,
    val targetTs: Long,
    val queryDesc: String,
    val blockChanges: List<BlockChangePlan>,
    val skippedBlockCount: Int,
) {
    val totalChangeCount: Int get() = blockChanges.size
}

enum class RollbackStatus(
    val value: String,
) {
    APPLIED("applied"),
    UNDONE("undone"),
    ;

    companion object {
        fun fromValue(value: String): RollbackStatus = entries.first { it.value == value }
    }
}

enum class RollbackChangeKind(
    val value: String,
) {
    BLOCK("block"),
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
    val queryDesc: String,
    val targetTs: Long,
    val status: RollbackStatus,
    val blockChangeCount: Int,
    val id: Long = 0,
)

data class RollbackChange(
    val operationId: Long,
    val changeKind: RollbackChangeKind,
    val id: Long = 0,
    val x: Int? = null,
    val y: Int? = null,
    val z: Int? = null,
    val beforeBlockState: String? = null,
    val beforeBlockNbt: ByteArray? = null,
    val afterBlockState: String? = null,
    val afterBlockNbt: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RollbackChange

        if (operationId != other.operationId) return false
        if (id != other.id) return false
        if (x != other.x) return false
        if (y != other.y) return false
        if (z != other.z) return false
        if (changeKind != other.changeKind) return false
        if (beforeBlockState != other.beforeBlockState) return false
        if (!beforeBlockNbt.contentEquals(other.beforeBlockNbt)) return false
        if (afterBlockState != other.afterBlockState) return false
        if (!afterBlockNbt.contentEquals(other.afterBlockNbt)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = operationId.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + (x ?: 0)
        result = 31 * result + (y ?: 0)
        result = 31 * result + (z ?: 0)
        result = 31 * result + changeKind.hashCode()
        result = 31 * result + (beforeBlockState?.hashCode() ?: 0)
        result = 31 * result + (beforeBlockNbt?.contentHashCode() ?: 0)
        result = 31 * result + (afterBlockState?.hashCode() ?: 0)
        result = 31 * result + (afterBlockNbt?.contentHashCode() ?: 0)
        return result
    }
}
