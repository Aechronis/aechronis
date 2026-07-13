package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Commands
import net.aechronis.vanilla.managers.Commands.MIRRORED_SLOTS
import net.aechronis.vanilla.managers.Commands.viewing
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.inventory.Inventory

object CommandsListener {
    fun onTeleport(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return
        Commands.saveLastLocation(player)
    }

    fun onChange(event: InventoryItemChangeEvent) {
        val inv = event.inventory as? Inventory ?: return
        val target = viewing[inv] ?: return
        val slot = event.slot
        if (slot !in 0..<MIRRORED_SLOTS) return
        target.inventory.setItemStack(slot, event.newItem)
    }

    fun onClose(event: InventoryCloseEvent) {
        val inv = event.inventory as? Inventory ?: return
        if (viewing.remove(inv) == null) return
        for (slot in MIRRORED_SLOTS until inv.size) {
            val stack = inv.getItemStack(slot)
            if (!stack.isAir) event.player.inventory.addItemStack(stack)
        }
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        Commands.removeLastSenderReferences(event.player)
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryItemChangeEvent::class.java, CommandsListener::onChange)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, CommandsListener::onClose)
        Vanilla.eventNode.addListener(EntityTeleportEvent::class.java, CommandsListener::onTeleport)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, CommandsListener::onDisconnect)
    }
}
