package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.PlaneState
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.tasks.VehicleTickManager
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent

object VehicleListener {
    fun onPlayerUseItemOnBlock(event: PlayerUseItemOnBlockEvent) {
        val player = event.player

        // check if player is already in a vehicle
        if (Vehicle.playerVehicle[player] != null) return
        if (Vehicle.passengerVehicle[player] != null) return

        // check if player is looking at a vehicle
        val lookingAtVehicle = VehicleTickManager.playerLookingAtVehicle[player]
        val lookingAtEntity = VehicleTickManager.playerLookingAtEntity[player]
        if (lookingAtVehicle != null && lookingAtEntity != null) {
            // check if vehicle already has a driver, if so, enter as passenger
            val hasDriver = Vehicle.playerVehicleEntity.values.any { it == lookingAtEntity }
            if (hasDriver) {
                lookingAtVehicle.onPassengerEnter(player, lookingAtEntity)
            } else {
                lookingAtVehicle.onEnter(player, lookingAtEntity)
            }
            return
        }

        // try to place a vehicle if holding one
        val vehicleItem = Item.getFromItemStack(player.itemInMainHand) as? Vehicle ?: return
        vehicleItem.place(
            player,
            event.position
                .asPos()
                .add(0.5, 1.0, 0.5)
                .withYaw(player.position.yaw),
        )
    }

    fun onPlayerDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player

        val vehicle = Vehicle.playerVehicle[player]
        if (vehicle != null) {
            // if player disconnects while flying a plane, destroy it
            if (vehicle is Plane) {
                val state = Plane.playerState[player]
                if (state == PlaneState.FLYING || state == PlaneState.TAKING_OFF) {
                    val entity = Vehicle.playerVehicleEntity[player]
                    if (entity != null) {
                        vehicle.destroy(entity)
                    }
                } else {
                    vehicle.onExit(player)
                }
            } else {
                vehicle.onExit(player)
            }
        }

        // clean up passenger state
        val passengerVehicle = Vehicle.passengerVehicle[player]
        if (passengerVehicle != null) {
            passengerVehicle.onPassengerExit(player)
        }

        VehicleTickManager.playerLookingAtVehicle.remove(player)
        VehicleTickManager.playerLookingAtEntity.remove(player)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, VehicleListener::onPlayerUseItemOnBlock)
        Combat.eventNode.addListener(PlayerDisconnectEvent::class.java, VehicleListener::onPlayerDisconnect)
    }
}
