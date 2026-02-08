package luna.nodes.combat.listeners

import luna.nodes.combat.Combat
import net.minestom.server.event.player.PlayerDisconnectEvent

object PlayerDisconnectListener {
    private fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player

        // cancel any active tasks before removing
        Combat.aimingResetTasks[player]?.cancel()
        Combat.firingResetTasks[player]?.cancel()
        Combat.reloadTasks[player]?.cancel()

        // remove player from all hashmaps to prevent memory leaks
        Combat.playerAiming.remove(player)
        Combat.aimingResetTasks.remove(player)
        Combat.playerFiring.remove(player)
        Combat.firingResetTasks.remove(player)
        Combat.reloadTasks.remove(player)
        Combat.playerPreviousPositions.remove(player)
        Combat.playerSpeeds.remove(player)
        Combat.playerKillers.remove(player)
        Combat.playerCooldowns.remove(player)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerDisconnectEvent::class.java, PlayerDisconnectListener::onPlayerDisconnect)
    }
}
