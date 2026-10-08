package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.VehicleCameraDistance
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
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
    health: Health,
    placeTime: Long = 1000,
    val maxSpeed: Float = 0.4f,
    val acceleration: Float = 0.02f,
    val braking: Float = 0.04f,
    val friction: Float = 0.98f,
    val turnSpeed: Float = 4.0f,
    val maxClimbHeight: Float = 0.5f,
    seats: List<VehicleSeat> = listOf(VehicleSeat("driver", "Driver", VehicleSeatRole.DRIVER)),
    invisibleWhileRiding: Boolean = false,
    invulnerableWhileRiding: Boolean = false,
    animatedParts: List<AnimatedPart> = emptyList(),
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
    modelScale: Vec = Vec(scale),
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
        seats,
        invisibleWhileRiding,
        invulnerableWhileRiding,
        animatedParts,
        collisionHitbox,
        modelScale,
    ) {
    override fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        // only allow one driver at a time and reject stale/direct mounts
        if (!canEnterAsDriver(player, entity)) return

        super.onEnter(player, entity)
        if (VehicleRegistry.driver(player)?.entity === entity) {
            VehicleCameraDistance.apply(player, hitbox, seats.first { it.role.drives }.offset)
        }
        playerSpeed[player] = 0f
    }

    override fun onExit(player: Player) {
        try {
            super.onExit(player)
        } finally {
            playerSpeed.remove(player)
            VehicleCameraDistance.restore(player)
        }
    }

    override fun onTick(player: Player) {
        val entity = VehicleRegistry.driver(player)?.entity ?: return
        var currentSpeed = playerSpeed[player] ?: 0f
        val inputEvent = KeyPressListener.playerInputEvent[player]

        // handle acceleration/braking
        when {
            inputEvent?.isHoldingForwardKey == true -> currentSpeed = min(currentSpeed + acceleration, maxSpeed)
            inputEvent?.isHoldingBackwardKey == true -> currentSpeed = max(currentSpeed - braking, -maxSpeed * 0.5f)
            else -> {
                currentSpeed *= friction
                // Stop residual coasting without cancelling small throttle inputs.
                if (abs(currentSpeed) < 0.005f) currentSpeed = 0f
            }
        }

        val position = entity.position
        // Validate movement and turning together before changing the live pose.
        var targetYaw = position.yaw
        if (inputEvent != null && abs(currentSpeed) > 0.01f) {
            val speedFactor = abs(currentSpeed) / maxSpeed
            when {
                inputEvent.isHoldingLeftKey -> targetYaw -= turnSpeed * speedFactor
                inputEvent.isHoldingRightKey -> targetYaw += turnSpeed * speedFactor
            }
        }

        playerSpeed[player] = currentSpeed

        val yawRad = Math.toRadians(targetYaw.toDouble())
        val dx = -sin(yawRad) * currentSpeed
        val dz = cos(yawRad) * currentSpeed
        val newX = position.x + dx
        val newZ = position.z + dz

        val instance = entity.instance ?: return
        if (!canStartMoving(instance, position)) {
            playerSpeed[player] = 0f
            super.onTick(player)
            return
        }

        val currentSurfaceY = getCurrentSurfaceY(position)
        val targetPosition = position.withX(newX).withZ(newZ).withYaw(targetYaw)
        val newSurfaceY = findSurfaceY(instance, targetPosition, currentSurfaceY)
        if (newSurfaceY == null) {
            playerSpeed[player] = 0f
            super.onTick(player)
            return
        }

        val newY = getVehicleY(newSurfaceY)
        val heightDelta = newY - position.y

        val newPos = targetPosition.withY(newY)
        if (heightDelta > maxClimbHeight || (newPos != position && !canMoveTo(instance, entity, newPos))) {
            playerSpeed[player] = 0f
        } else if (newPos != position) {
            entity.teleport(newPos)
        }

        super.onTick(player)
    }

    protected open fun canStartMoving(
        instance: Instance,
        position: Pos,
    ): Boolean = true

    protected open fun canMoveTo(
        instance: Instance,
        entity: Entity,
        position: Pos,
    ): Boolean = true

    protected open fun getCurrentSurfaceY(position: Pos): Double = position.y - hitbox.getGroundOffset()

    protected open fun getVehicleY(surfaceY: Double): Double = surfaceY + hitbox.getGroundOffset()

    protected open fun findSurfaceY(
        instance: Instance,
        position: Pos,
        currentSurfaceY: Double,
    ): Double? {
        val startY = floor(currentSurfaceY + maxClimbHeight + 1).toInt()
        val endY = floor(currentSurfaceY - 10).toInt()
        for (y in startY downTo endY) {
            val block = instance.getBlock(position.blockX(), y, position.blockZ())
            if (block.isSolid) {
                return (y + 1).toDouble()
            }
        }
        return null
    }

    companion object {
        val playerSpeed = hashMapOf<Player, Float>()

        internal fun shutdownRuntimeState() {
            playerSpeed.clear()
        }
    }
}
