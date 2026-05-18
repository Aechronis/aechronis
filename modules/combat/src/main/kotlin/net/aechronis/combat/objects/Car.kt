package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

open class Car(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Float = 100F,
    placeTime: Long = 1000,
    val maxSpeed: Float = 0.4f,
    val acceleration: Float = 0.02f,
    val braking: Float = 0.04f,
    val friction: Float = 0.98f,
    val turnSpeed: Float = 4.0f,
    val maxClimbHeight: Float = 0.5f,
    seatOffsets: List<Vec> = listOf(Vec.ZERO),
) : Vehicle(
        name,
        itemName,
        itemLore,
        itemModel,
        model,
        scale,
        hitbox,
        health,
        placeTime,
        seatOffsets,
    ) {
    override fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        // only allow one driver at a time
        if (playerVehicleEntity.values.any { it == entity }) return

        super.onEnter(player, entity)
        playerSpeed[player] = 0f
    }

    override fun onExit(player: Player) {
        playerSpeed.remove(player)
        super.onExit(player)
    }

    override fun onTick(player: Player) {
        val entity = playerVehicleEntity[player] ?: return
        var currentSpeed = playerSpeed[player] ?: 0f
        val inputEvent = KeyPressListener.playerInputEvent[player]

        // handle acceleration/braking
        when {
            inputEvent?.isHoldingForwardKey == true -> currentSpeed = min(currentSpeed + acceleration, maxSpeed)
            inputEvent?.isHoldingBackwardKey == true -> currentSpeed = max(currentSpeed - braking, -maxSpeed * 0.5f)
            else -> currentSpeed *= friction
        }

        // handle turning (only when moving)
        if (inputEvent != null && abs(currentSpeed) > 0.01f) {
            val speedFactor = abs(currentSpeed) / maxSpeed
            val currentYaw = entity.position.yaw
            when {
                inputEvent.isHoldingLeftKey -> entity.setView(currentYaw - turnSpeed * speedFactor, 0f)
                inputEvent.isHoldingRightKey -> entity.setView(currentYaw + turnSpeed * speedFactor, 0f)
            }
        }

        // stop if very slow
        if (abs(currentSpeed) < 0.005f) currentSpeed = 0f
        playerSpeed[player] = currentSpeed

        val position = entity.position

        val yawRad = Math.toRadians(position.yaw.toDouble())
        val dx = -sin(yawRad) * currentSpeed
        val dz = cos(yawRad) * currentSpeed
        val newX = position.x + dx
        val newZ = position.z + dz

        val instance = entity.instance ?: return
        val currentGroundY = position.y - hitbox.getGroundOffset()
        val newGroundY = findGroundY(instance, newX, newZ, currentGroundY)
        val heightDelta = newGroundY - currentGroundY

        // checks if we can climb/drop
        val newPos =
            if (heightDelta <= maxClimbHeight) {
                position.withX(newX).withZ(newZ).withY(newGroundY + hitbox.getGroundOffset())
            } else {
                position
            }

        entity.teleport(newPos)

        super.onTick(player)
    }

    private fun findGroundY(
        instance: Instance,
        x: Double,
        z: Double,
        currentY: Double,
    ): Double {
        val startY = (currentY + maxClimbHeight + 1).toInt()
        val endY = (currentY - 10).toInt()
        for (y in startY downTo endY) {
            val block = instance.getBlock(x.toInt(), y, z.toInt())
            if (block.isSolid) {
                return (y + 1).toDouble()
            }
        }
        return currentY
    }

    companion object {
        val playerSpeed = hashMapOf<Player, Float>()
    }
}
