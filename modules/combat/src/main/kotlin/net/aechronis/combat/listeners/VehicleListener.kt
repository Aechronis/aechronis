package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Ship
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.tasks.VehicleTickManager
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import kotlin.math.floor

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

    fun onPlayerUseItem(event: PlayerUseItemEvent) {
        val player = event.player

        val ship = Item.getFromItemStack(player.itemInMainHand) as? Ship ?: return
        if (Vehicle.playerVehicle[player] != null) return
        if (Vehicle.passengerVehicle[player] != null) return

        val eyePosition = player.position.add(0.0, player.eyeHeight, 0.0)
        val target = findWaterPlacementPosition(player.instance, eyePosition, eyePosition.direction()) ?: return
        if (ship.place(player, target)) event.isCancelled = true
    }

    internal fun findWaterPlacementPosition(
        instance: Instance,
        eyePosition: Pos,
        direction: Vec,
    ): Pos? {
        var distance = 0.0
        while (distance <= 5.0) {
            val point = eyePosition.add(direction.mul(distance))
            val blockX = floor(point.x).toInt()
            val blockY = floor(point.y).toInt()
            val blockZ = floor(point.z).toInt()
            if (instance.getBlock(blockX, blockY, blockZ).compare(Block.WATER)) {
                return Pos(blockX + 0.5, blockY + 1.0, blockZ + 0.5).withYaw(eyePosition.yaw)
            }
            distance += 0.1
        }
        return null
    }

    fun init() {
        Combat.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, VehicleListener::onPlayerUseItemOnBlock)
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, VehicleListener::onPlayerUseItem)
    }
}
