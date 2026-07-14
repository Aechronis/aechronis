package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent

object CooldownResetListener {
    fun onPlayerSwap(event: PlayerChangeHeldSlotEvent) {
        resetCooldown(event.player)
    }

    private fun resetCooldown(player: Player) {
        val now = System.currentTimeMillis()
        Combat.playerLastActionTimes[player] = now
        Combat.meleeLastAttackTimes[player] = now
    }

    fun init() {
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, CooldownResetListener::onPlayerSwap)
    }
}
