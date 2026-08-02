package net.aechronis.logger.objects

import net.aechronis.logger.utils.LogMetadata
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import java.util.UUID

enum class EntityChangeAction(
    val value: String,
) {
    SPAWN("spawn"),
    DESPAWN("despawn"),
    ;

    companion object {
        fun fromValue(value: String): EntityChangeAction = entries.first { it.value == value }
    }
}

data class EntityChange(
    val timestamp: Long,
    val entityUuid: UUID,
    val entityType: String,
    val action: EntityChangeAction,
    val instanceUuid: UUID,
    val position: Pos,
    val velocity: Vec,
    val tagData: ByteArray?,
    val playerUuid: UUID? = null,
    val playerName: String? = null,
    val source: String = LogMetadata.LOGGER,
    val origin: String = LogMetadata.LOGGER,
    val rolledBack: Boolean = false,
    val id: Long = 0,
)
