package net.aechronis.logger.objects

import net.minestom.server.item.ItemStack
import java.util.UUID

enum class InventorySnapshotAction(
    val value: String,
) {
    DEATH("death"),
    LOGIN("login"),
    LOGOUT("logout"),
    ;

    companion object {
        fun fromValue(value: String): InventorySnapshotAction = entries.first { it.value == value }
    }
}

data class InventorySnapshot(
    val timestamp: Long,
    val playerUuid: UUID,
    val playerName: String,
    val action: InventorySnapshotAction,
    val items: List<ItemStack>,
    val source: String = LogMetadata.LOGGER,
    val origin: String = LogMetadata.LOGGER,
    val id: Long = 0,
)
