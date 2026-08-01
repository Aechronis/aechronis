package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Commands
import net.aechronis.vanilla.managers.Commands.MIRRORED_SLOTS
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.inventory.Inventory

object CommandsListener {
    fun onTeleport(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return
        Commands.saveLastLocation(player)
    }

    fun onChange(event: InventoryItemChangeEvent) {
        Commands.synchronizeInventoryChange(event.inventory, event.slot, event.newItem)
    }

    fun onClose(event: InventoryCloseEvent) {
        val inv = event.inventory as? Inventory ?: return
        if (!Commands.removeView(inv)) return
        for (slot in MIRRORED_SLOTS until inv.size) {
            val stack = inv.getItemStack(slot)
            if (!stack.isAir && !event.player.inventory.addItemStack(stack)) {
                event.player.dropItem(stack)
            }
        }
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        Commands.removeLastSenderReferences(event.player)
        if (!Vanilla.config.playerDataEnabled) {
            Commands.closeViewsOf(event.player)
            Commands.removeEnderChest(event.player)
        }
    }

    fun onSpawn(event: PlayerSpawnEvent) {
        Commands.allowEnderChest(event.player)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryItemChangeEvent::class.java, CommandsListener::onChange)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, CommandsListener::onClose)
        Vanilla.eventNode.addListener(EntityTeleportEvent::class.java, CommandsListener::onTeleport)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, CommandsListener::onDisconnect)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, CommandsListener::onSpawn)
    }
}
