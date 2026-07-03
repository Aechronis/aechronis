package net.aechronis.logger.objects

import java.util.UUID

data class BlockLogEntry(
    val timestamp: Long,
    val playerUuid: UUID,
    val playerName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val blockOld: String,
    val blockNew: String,
    val action: BlockAction,
    val id: Long = 0,
    val instanceUuid: UUID? = null,
    val blockOldState: String? = null,
    val blockNewState: String? = null,
    val blockOldNbt: ByteArray? = null,
    val blockNewNbt: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BlockLogEntry

        if (timestamp != other.timestamp) return false
        if (x != other.x) return false
        if (y != other.y) return false
        if (z != other.z) return false
        if (id != other.id) return false
        if (playerUuid != other.playerUuid) return false
        if (playerName != other.playerName) return false
        if (blockOld != other.blockOld) return false
        if (blockNew != other.blockNew) return false
        if (action != other.action) return false
        if (instanceUuid != other.instanceUuid) return false
        if (blockOldState != other.blockOldState) return false
        if (blockNewState != other.blockNewState) return false
        if (!blockOldNbt.contentEquals(other.blockOldNbt)) return false
        if (!blockNewNbt.contentEquals(other.blockNewNbt)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + x
        result = 31 * result + y
        result = 31 * result + z
        result = 31 * result + id.hashCode()
        result = 31 * result + playerUuid.hashCode()
        result = 31 * result + playerName.hashCode()
        result = 31 * result + blockOld.hashCode()
        result = 31 * result + blockNew.hashCode()
        result = 31 * result + action.hashCode()
        result = 31 * result + (instanceUuid?.hashCode() ?: 0)
        result = 31 * result + (blockOldState?.hashCode() ?: 0)
        result = 31 * result + (blockNewState?.hashCode() ?: 0)
        result = 31 * result + (blockOldNbt?.contentHashCode() ?: 0)
        result = 31 * result + (blockNewNbt?.contentHashCode() ?: 0)
        return result
    }
}
