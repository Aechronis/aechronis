package net.aechronis.logger.listeners

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.InventorySnapshotAction
import net.aechronis.logger.objects.snapshotItems
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent

object InventorySnapshotListener {
    fun init() {
        Logger.eventNode.addListener(PlayerDeathEvent::class.java) { event ->
            capture(event.player, InventorySnapshotAction.DEATH)
        }
        Logger.eventNode.addListener(PlayerDisconnectEvent::class.java) { event ->
            capture(event.player, InventorySnapshotAction.LOGOUT)
        }
    }

    private fun capture(
        player: Player,
        action: InventorySnapshotAction,
    ) {
        // A disconnect may be delivered by a network thread while shutdown is unwinding.
        // The coordinated shutdown captures normal disconnects before this becomes false.
        if (!Logger.isAcceptingLogs) return

        val items = snapshotItems(player.inventory)
        val result =
            when (action) {
                InventorySnapshotAction.DEATH -> {
                    Logger.inventorySnapshot.deathAsync(player.uuid, player.username, items)
                }

                InventorySnapshotAction.LOGOUT -> {
                    Logger.inventorySnapshot.logoutAsync(player.uuid, player.username, items)
                }

                else -> {
                    return
                }
            }
        result.exceptionally { failure ->
            println("[Logger] failed to record ${action.value} inventory snapshot: $failure")
            null
        }
    }
}
