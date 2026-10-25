package net.aechronis.crates.listeners

import net.aechronis.crates.Crates
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventNode
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.inventory.Inventory

object CratesListener {
    // Finish a disconnected player's reward before vanilla applies combat penalties and saves inventory.
    private val eventNode = EventNode.all("crates").setPriority(-1)

    fun onHandAnimation(event: PlayerHandAnimationEvent) {
        if (event.isCancelled || event.hand != PlayerHand.MAIN) return

        val item = event.player.itemInMainHand
        if (Crates.crateFor(item) == null) return

        event.isCancelled = true
        Crates.openCrate(event.player, item)
    }

    fun onPreClick(event: InventoryPreClickEvent) {
        val inventory = event.inventory as? Inventory ?: return
        if (Crates.isCrateInventory(inventory)) event.isCancelled = true
    }

    fun onClose(event: InventoryCloseEvent) {
        val inventory = event.inventory as? Inventory ?: return
        Crates.closeInventory(event.player, inventory)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        Crates.disconnect(event.player)
    }

    fun init() {
        eventNode.addListener(PlayerHandAnimationEvent::class.java, CratesListener::onHandAnimation)
        eventNode.addListener(InventoryPreClickEvent::class.java, CratesListener::onPreClick)
        eventNode.addListener(InventoryCloseEvent::class.java, CratesListener::onClose)
        eventNode.addListener(PlayerDisconnectEvent::class.java, CratesListener::onDisconnect)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
    }
}
