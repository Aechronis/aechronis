package net.aechronis.logger.objects

import net.aechronis.logger.utils.LogMetadata
import net.minestom.server.item.ItemStack
import java.util.UUID

enum class InventoryChangeAction(
    val value: String,
) {
    ADD("add"),
    REMOVE("remove"),
    CHANGE("change"),
    ;

    companion object {
        fun fromValue(value: String): InventoryChangeAction = entries.first { it.value == value }
    }
}

data class InventoryChange(
    val timestamp: Long,
    val playerUuid: UUID,
    val playerName: String,
    val slot: Int,
    val action: InventoryChangeAction,
    val oldItem: ItemStack,
    val newItem: ItemStack,
    val source: String = LogMetadata.LOGGER,
    val origin: String = LogMetadata.LOGGER,
    val rolledBack: Boolean = false,
    val id: Long = 0,
)
