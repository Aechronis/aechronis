package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.tasks.VehicleTickManager
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

    fun init() {
        Combat.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, VehicleListener::onPlayerUseItemOnBlock)
    }
}
