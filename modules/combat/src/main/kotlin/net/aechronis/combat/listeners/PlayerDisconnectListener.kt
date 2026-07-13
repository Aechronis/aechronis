package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.PlaneState
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.storage.HatCollection
import net.aechronis.combat.tasks.VehicleTickManager
import net.minestom.server.event.player.PlayerDisconnectEvent

object PlayerDisconnectListener {
    private fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player

        // vehicle
        val vehicle = Vehicle.playerVehicle[player]
        if (vehicle != null) {
            if (vehicle is Plane) {
                val state = Plane.playerState[player]
                if (state == PlaneState.FLYING || state == PlaneState.TAKING_OFF) {
                    val entity = Vehicle.playerVehicleEntity[player]
                    if (entity != null) {
                        vehicle.destroy(entity)
                    } else {
                        vehicle.onExit(player)
                    }
                } else {
                    vehicle.onExit(player)
                }
            } else {
                vehicle.onExit(player)
            }
        }

        Vehicle.passengerVehicle[player]?.onPassengerExit(player)

        VehicleTickManager.playerLookingAtVehicle.remove(player)
        VehicleTickManager.playerLookingAtEntity.remove(player)

        // cancel any active tasks before removing
        Combat.aimingResetTasks.remove(player)?.cancel()
        Combat.reloadTasks.remove(player)?.cancel()
        Combat.placeTasks.remove(player)?.cancel()

        // remove player from all hashmaps to prevent memory leaks
        Combat.playerAiming.remove(player)
        Combat.playerPreviousPositions.remove(player)
        Combat.playerSpeeds.remove(player)
        Combat.removeKillerReferences(player)
        Combat.playerLastActionTimes.remove(player)
        Combat.entityLastDamageTime.remove(player)
        KeyPressListener.playerInputEvent.remove(player)
        Hitbox.viewingHitboxes.remove(player)

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
