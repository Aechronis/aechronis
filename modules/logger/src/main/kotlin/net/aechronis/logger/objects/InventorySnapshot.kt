package net.aechronis.logger.objects

import net.aechronis.logger.utils.LogMetadata
import net.minestom.server.inventory.PlayerInventory
import net.minestom.server.item.ItemStack
import java.util.UUID

val survivalInventorySlots: List<Int> = (0..35).toList() + (41..45)

fun snapshotItems(inventory: PlayerInventory): List<ItemStack> =
    List(PlayerInventory.INVENTORY_SIZE) { slot ->
        if (slot in survivalInventorySlots) inventory.getItemStack(slot) else ItemStack.AIR
    }

enum class InventorySnapshotAction(
    val value: String,
) {
    DEATH("death"),
    LOGIN("login"),
    LOGOUT("logout"),
    LEGACY("legacy"),
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
