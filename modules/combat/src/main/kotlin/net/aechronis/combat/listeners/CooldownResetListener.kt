package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent

object CooldownResetListener {
    fun onPlayerSwap(event: PlayerChangeHeldSlotEvent) {
        resetCooldown(event.player)
    }

    fun onInventoryClick(event: InventoryPreClickEvent) {
        resetCooldown(event.player)
    }

    private fun resetCooldown(player: Player) {
        Combat.playerLastActionTimes[player] = System.currentTimeMillis()
    }

    fun init() {
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, CooldownResetListener::onPlayerSwap)
        Combat.eventNode.addListener(InventoryPreClickEvent::class.java, CooldownResetListener::onInventoryClick)
    }
}
