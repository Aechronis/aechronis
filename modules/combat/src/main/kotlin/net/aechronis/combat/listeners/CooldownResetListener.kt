package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent

object CooldownResetListener {
    fun onPlayerSwap(event: PlayerChangeHeldSlotEvent) {
        Combat.playerCooldowns[event.player] = 0
    }

    fun onInventoryClick(event: InventoryPreClickEvent) {
        Combat.playerCooldowns[event.player] = 0
    }

    fun init() {
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, CooldownResetListener::onPlayerSwap)
        Combat.eventNode.addListener(InventoryPreClickEvent::class.java, CooldownResetListener::onInventoryClick)
    }
}
