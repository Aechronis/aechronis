package net.aechronis.logger.objects

import net.minestom.server.item.ItemStack
import java.util.UUID

enum class StorageChangeAction(
    val value: String,
) {
    WITHDRAW("withdraw"),
    DEPOSIT("deposit"),
    ;

    companion object {
        fun fromValue(value: String): StorageChangeAction = entries.first { it.value == value }
    }
}

data class StorageChange(
    val timestamp: Long,
    val storageId: String,
    val action: StorageChangeAction,
    val item: ItemStack,
    val amount: Int,
    val playerUuid: UUID? = null,
    val playerName: String? = null,
    val source: String = LogMetadata.LOGGER,
    val origin: String = LogMetadata.LOGGER,
    val id: Long = 0,
)
