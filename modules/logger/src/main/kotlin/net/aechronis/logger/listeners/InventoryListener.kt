package net.aechronis.logger.listeners

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.InventoryChange
import net.aechronis.logger.objects.InventoryChangeAction
import net.aechronis.logger.objects.RollbackMutationGuard
import net.minestom.server.MinecraftServer
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.item.Material

object InventoryListener {
    fun init() {
        Logger.eventNode.addListener(InventoryItemChangeEvent::class.java) { event ->
            val player =
                MinecraftServer.getConnectionManager().onlinePlayers.firstOrNull { it.inventory === event.inventory }
                    ?: return@addListener
            if (RollbackMutationGuard.inventorySuppressed(player.uuid)) return@addListener
            val oldItem = event.previousItem
            val newItem = event.newItem
            val action =
                when {
                    oldItem.material() == Material.AIR && newItem.material() != Material.AIR -> InventoryChangeAction.ADD
                    oldItem.material() != Material.AIR && newItem.material() == Material.AIR -> InventoryChangeAction.REMOVE
                    oldItem.isSimilar(newItem) && newItem.amount() > oldItem.amount() -> InventoryChangeAction.ADD
                    oldItem.isSimilar(newItem) && newItem.amount() < oldItem.amount() -> InventoryChangeAction.REMOVE
                    else -> InventoryChangeAction.CHANGE
                }
            Logger.inventoryChange
                .insertAsync(
                    InventoryChange(
                        timestamp = System.currentTimeMillis(),
                        playerUuid = player.uuid,
                        playerName = player.username,
                        slot = event.slot,
                        action = action,
                        oldItem = oldItem,
                        newItem = newItem,
                    ),
                ).exceptionally { exception ->
                    println("[Logger] failed to record inventory change: $exception")
                    null
                }
        }
    }
}
