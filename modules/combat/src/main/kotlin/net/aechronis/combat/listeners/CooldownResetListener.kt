package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.utils.GunAnimation
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent

object CooldownResetListener {
    fun onPlayerSwap(event: PlayerChangeHeldSlotEvent) {
        if (event.isCancelled) return
        if (event.oldSlot != event.newSlot) GunAnimation.cancel(event.player)
        resetCooldown(event.player)
    }

    private fun resetCooldown(player: Player) {
        Combat.playerStates.getOrCreate(player).lastActionTime = System.currentTimeMillis()
    }

    fun init() {
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, CooldownResetListener::onPlayerSwap)
    }
}
