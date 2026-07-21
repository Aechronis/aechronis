package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.rotatePoint
import net.aechronis.combat.utils.setRoll
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import kotlin.math.abs

enum class PlaneState {
    LANDED,
    TAKING_OFF,
    FLYING,
    CRASHED,
}

// weapon on a plane, i'll add projectiles to this at some point
data class PlaneWeapon(
    val gun: Gun,
    val firePoints: List<Vec>,
)

class Plane(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Float = 1000F,
    placeTime: Long = 3000,
    val speed: Double = 0.5,
    val turnSpeed: Float = 0.05f,
    val throttleStep: Float = 5f,
    val landingThrottle: Float = 35f,
    val maxThrottle: Float = 100f,
    val minAirThrottle: Float = 30f,
    val weapons: List<PlaneWeapon> = emptyList(),
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
    ) {
    override fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        // only allow one pilot at a time
        if (playerVehicleEntity.values.any { it == entity }) return

        super.onEnter(player, entity)
        (entity.entityMeta as ItemDisplayMeta).setTransformationInterpolationDuration(3)
        playerRoll[player] = 0f
        playerState[player] = PlaneState.LANDED
        takeoffCounter[player] = 0
        playerThrottle[player] = 0f
    }

    override fun onExit(player: Player) {
        super.onExit(player)
        playerRoll.remove(player)
        playerState.remove(player)
        takeoffCounter.remove(player)
        playerThrottle.remove(player)
    }

    override fun destroy(entity: Entity) {
        // explosion at plane position
        val instance = entity.instance
        if (instance != null) {
            Explosion(instance, entity.position, 5, 0.5)
        }
        super.destroy(entity)
    }

    override fun onTick(player: Player) {
        val entity = playerVehicleEntity[player] ?: return
        var roll = playerRoll[player] ?: 0f
        var state = playerState[player] ?: PlaneState.LANDED

        if (state == PlaneState.CRASHED) {
            return
        }

        val inputEvent = KeyPressListener.playerInputEvent[player]

        // handle takeoff
        if (state == PlaneState.LANDED && inputEvent?.isHoldingForwardKey == true) {
            playerState[player] = PlaneState.TAKING_OFF
            takeoffCounter[player] = 200
            state = PlaneState.TAKING_OFF
        }

        var throttle = playerThrottle[player] ?: 0f
        if (state == PlaneState.LANDED) {
            throttle = 0f
        } else {
            when {
                inputEvent?.isHoldingForwardKey == true -> throttle += throttleStep
                inputEvent?.isHoldingBackwardKey == true -> throttle -= throttleStep
            }
            throttle = throttle.coerceIn(minAirThrottle, maxThrottle)
        }
        playerThrottle[player] = throttle

        if (state == PlaneState.FLYING || state == PlaneState.TAKING_OFF) {
            entity.setView(entity.position.yaw, player.position.pitch.coerceIn(-60f, 60f))

            val targetRoll =
                when {
                    inputEvent?.isHoldingLeftKey == true && inputEvent.isHoldingRightKey != true -> -45f
                    inputEvent?.isHoldingRightKey == true && inputEvent.isHoldingLeftKey != true -> 45f
                    else -> 0f
                }
            roll = approach(roll, targetRoll, 1.5f)
            playerRoll[player] = roll
        }

        val position = entity.position
        val direction = position.direction()

        // check for collision
        val isColliding =
            hitbox.checkGroundCollision(player.instance, position, position.yaw, position.pitch, roll)

        // handle collision
        if (isColliding && state == PlaneState.FLYING) {
            val isSafeLanding =
                throttle <= landingThrottle &&
                    abs(position.pitch) < 10f &&
                    abs(roll) < 10f

            if (isSafeLanding) {
                playerState[player] = PlaneState.LANDED
                playerRoll[player] = 0f
                playerThrottle[player] = 0f
                return
            } else {
                playerState[player] = PlaneState.CRASHED
                destroy(entity)
                return
            }
        }

        // handle taking off state
        if (state == PlaneState.TAKING_OFF) {
            val counter = takeoffCounter[player] ?: 0
            if (counter <= 0) {
                playerState[player] = PlaneState.FLYING
                state = PlaneState.FLYING
            } else {
                takeoffCounter[player] = counter - 1
            }
        }

        if (state == PlaneState.FLYING || state == PlaneState.TAKING_OFF) {
            val throttleSpeed = speed * throttle / maxThrottle
            entity.teleport(position.add(direction.mul(throttleSpeed)).withYaw(position.yaw + roll * turnSpeed))
        }

        val meta = entity.entityMeta as ItemDisplayMeta
        meta.leftRotation = setRoll((playerRoll[player] ?: 0f) / 55)

        // fire guns when holding space while flying
        if ((state == PlaneState.FLYING || state == PlaneState.TAKING_OFF) && inputEvent?.isHoldingJumpKey == true) {
            fireGuns(player)
        }

        super.onTick(player)
    }

    private fun approach(
        current: Float,
        target: Float,
        maxStep: Float,
    ): Float =
        when {
            target - current > maxStep -> current + maxStep
            target - current < -maxStep -> current - maxStep
            else -> target
        }

    // fires all weapons from this plane
    private fun fireGuns(player: Player) {
        if (weapons.isEmpty()) return

        val entity = playerVehicleEntity[player] ?: return
        val position = entity.position
        val roll = playerRoll[player] ?: 0f

        for ((_, mount) in weapons.withIndex()) {
            // fire from each fire point
            for (localPoint in mount.firePoints) {
                // local point to world space
                val worldOffset = rotatePoint(localPoint, position.yaw, position.pitch, roll)
                val firePos = position.add(worldOffset.x, worldOffset.y, worldOffset.z)

                // fire the gun
                mount.gun.fire(
                    player,
                    firePos,
                    ignoreCooldown = true,
                    ignoreAmmo = true,
                )
            }
        }
    }

    companion object {
        var playerRoll = hashMapOf<Player, Float>()
        var playerState = hashMapOf<Player, PlaneState>()
        var takeoffCounter = hashMapOf<Player, Int>()
        var playerThrottle = hashMapOf<Player, Float>()
    }
}
