package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerInputEvent

object KeyPressListener {
    var playerInputEvent: HashMap<Player, PlayerInputEvent> = hashMapOf()

    private fun onPlayerInput(event: PlayerInputEvent) {
        playerInputEvent[event.player] = event
    }

    fun init() {
        Combat.eventNode.addListener(PlayerInputEvent::class.java, KeyPressListener::onPlayerInput)
    }
}
