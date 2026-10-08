package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Boat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.TurretScope
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleCollisionEntity
import net.aechronis.combat.objects.VehicleDisplayEntity
import net.aechronis.combat.objects.VehicleRegistry
import net.aechronis.combat.objects.VehicleSeatHotbar
import net.aechronis.combat.tasks.VehicleTickManager
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.entity.EntityDespawnEvent
import net.minestom.server.event.instance.RemoveEntityFromInstanceEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import kotlin.math.floor

object VehicleListener {
    fun onPlayerUseItemOnBlock(event: PlayerUseItemOnBlockEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val player = event.player
        Vehicle.reconcileOccupant(player)

        // check if player is already in a vehicle
        if (VehicleRegistry.ride(player) != null) return

        if (enterLookedAtVehicle(player)) return

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
        if (event.hand != PlayerHand.MAIN) return
        val player = event.player
        Vehicle.reconcileOccupant(player)

        if (VehicleRegistry.ride(player) != null) return
        if (enterLookedAtVehicle(player)) {
            event.isCancelled = true
            return
        }
        val boat = Item.getFromItemStack(player.itemInMainHand) as? Boat ?: return

        val eyePosition = player.position.add(0.0, player.eyeHeight, 0.0)
        val target = findWaterPlacementPosition(player.instance, eyePosition, eyePosition.direction()) ?: return
        if (boat.place(player, target)) event.isCancelled = true
    }

    private fun onPlayerEntityInteract(event: PlayerEntityInteractEvent) {
        if (event.hand != PlayerHand.MAIN || (event.target !is VehicleCollisionEntity && event.target !is VehicleDisplayEntity)) return
        Vehicle.reconcileOccupant(event.player)
        if (VehicleRegistry.ride(event.player) == null) enterLookedAtVehicle(event.player)
    }

    private fun enterLookedAtVehicle(player: Player): Boolean {
        val instance = player.instance ?: return false
        val eye = player.position.add(0.0, player.eyeHeight, 0.0)
        // A shulker may intercept the client's click. Selection still uses the original
        // detailed hitbox and reach, including when the physical hull is larger.
        val target =
            VehicleTickManager.findLookedAtVehicle(
                instance,
                eye,
                eye.direction().mul(3.0),
                VehicleTickManager.prepareVehicleLookIndex(VehicleRegistry.all().map { it.entity to it.vehicle }),
            ) ?: return false
        target.vehicle.board(player, target.entity)
        return true
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

    fun onEntityDespawn(event: EntityDespawnEvent) {
        Vehicle.invalidateEntity(event.entity)
        VehicleRegistry.remove(event.entity)
    }

    fun onEntityRemovedFromInstance(event: RemoveEntityFromInstanceEvent) {
        Vehicle.invalidateEntity(event.entity)
        VehicleRegistry.runtime(event.entity)?.removeCollisionHitbox()
    }

    fun init() {
        VehicleSeatHotbar.initListeners()
        TurretScope.initListeners()
        Combat.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, VehicleListener::onPlayerUseItemOnBlock)
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, VehicleListener::onPlayerUseItem)
        Combat.eventNode.addListener(PlayerEntityInteractEvent::class.java, VehicleListener::onPlayerEntityInteract)
        Combat.eventNode.addListener(EntityDespawnEvent::class.java, VehicleListener::onEntityDespawn)
        Combat.eventNode.addListener(RemoveEntityFromInstanceEvent::class.java, VehicleListener::onEntityRemovedFromInstance)
    }
}
