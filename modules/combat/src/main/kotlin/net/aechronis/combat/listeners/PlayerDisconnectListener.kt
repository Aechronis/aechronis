package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.storage.HatCollection
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

        // clean up car state
        Car.playerSpeed.remove(player)

        // clean up vehicle passenger state
        Vehicle.passengerVehicle.remove(player)
        Vehicle.passengerVehicleEntity.remove(player)
        Vehicle.passengerSeatEntity.remove(player)?.remove()

        // clean up hat menu entities
        HatListener.playerCamera.remove(player.uuid)?.remove()
        HatListener.playerMannequin.remove(player.uuid)?.remove()

        // save and unload hat collection
        HatCollection.unload(player.uuid)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerDisconnectEvent::class.java, PlayerDisconnectListener::onPlayerDisconnect)
    }
}
