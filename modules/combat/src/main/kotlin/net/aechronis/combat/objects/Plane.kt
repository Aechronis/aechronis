package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.VehicleCameraDistance
import net.aechronis.combat.utils.rotatePoint
import net.aechronis.combat.utils.setRoll
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.instance.Instance
import net.minestom.server.particle.Particle
import kotlin.math.abs
import kotlin.math.ceil

enum class PlaneState {
    LANDED,
    TAKING_OFF,
    FLYING,
    CRASHED,
}

private data class DiveState(
    var speed: Double,
    var pitch: Float,
    val roll: Float,
)

// weapon on a plane, i'll add projectiles to this at some point
data class PlaneWeapon(
    val gun: Gun,
    val firePoints: List<Vec>,
)

data class PlaneBombWeapon(
    val releaseOffset: Vec = Vec(0.0, -1.5, 0.0),
    val projectileModel: String,
    val projectileName: Component = Component.text("Bomb"),
    val projectileSpeed: Double = 4.0,
    val projectileExplosionRadius: Int = 4,
    val projectileExplosionFire: Double = 0.1,
    val projectileExplosionDamage: Float = 20f,
    val fireCooldown: Long = 20_000,
    val projectileTrailParticle: Particle? = Particle.ELECTRIC_SPARK,
    val projectileTrailSpacing: Double = 1.0,
    val projectileTrailMaxParticles: Int = 96,
    val projectileMaxRange: Double = 128.0,
) {
    init {
        require(projectileSpeed > 0.0 && projectileSpeed.isFinite()) {
            "Plane bomb projectileSpeed must be a positive finite number"
        }
        require(projectileTrailSpacing > 0.0 && projectileTrailSpacing.isFinite()) {
            "Plane bomb projectileTrailSpacing must be positive and finite"
        }
        require(projectileTrailMaxParticles >= 2) { "Plane bomb projectileTrailMaxParticles must be at least two" }
        require(projectileMaxRange > 0.0 && projectileMaxRange.isFinite()) {
            "Plane bomb projectileMaxRange must be positive and finite"
        }
        require(fireCooldown >= 0) { "Plane bomb fireCooldown cannot be negative" }
    }
}

class Plane(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Health,
    placeTime: Long = 3000,
    val speed: Double = 0.5,
    val turnSpeed: Float = 0.05f,
    val throttleStep: Float = 5f,
    val throttleDecay: Float = 0.25f,
    val landingThrottle: Float = 35f,
    val maxThrottle: Float = 100f,
    val minAirThrottle: Float = 30f,
    /** Maximum downward pitch an unattended plane reaches while diving. */
    val divePitch: Float = 80f,
    /** Degrees the unattended plane pitches down per tick. */
    val divePitchSpeed: Float = 2f,
    /** Additional blocks per tick gained by an unattended plane each tick. */
    val diveAcceleration: Double = 0.02,
    /** Maximum blocks per tick for an unattended plane. */
    val maxDiveSpeed: Double = speed * 2.0,
    override val ammo: Ammo,
    override val maxAmmo: Int,
    val weapons: List<PlaneWeapon> = emptyList(),
    val bomb: PlaneBombWeapon? = null,
    val explosionDamage: Float = 20f,
    val seatOffset: List<Vec> = listOf(Vec.ZERO),
    invisibleWhileRiding: Boolean = true,
    invulnerableWhileRiding: Boolean = true,
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
        seatOffset,
        invisibleWhileRiding,
        invulnerableWhileRiding,
    ),
    ArmedVehicle {
    init {
        require(maxAmmo > 0) { "Plane maxAmmo must be greater than zero" }
        require(divePitch in 0f..90f) { "Plane divePitch must be between 0 and 90" }
        require(divePitchSpeed > 0f && divePitchSpeed.isFinite()) { "Plane divePitchSpeed must be positive and finite" }
        require(diveAcceleration > 0.0 && diveAcceleration.isFinite()) { "Plane diveAcceleration must be positive and finite" }
        require(maxDiveSpeed > 0.0 && maxDiveSpeed.isFinite()) { "Plane maxDiveSpeed must be positive and finite" }
    }

    override fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        // only allow one pilot at a time, and never let a pilot reclaim a plane
        // that is already in an uncontrolled dive.
        if (!canEnterAsDriver(player, entity) || entityDives.containsKey(entity)) return

        super.onEnter(player, entity)
        (entity.entityMeta as ItemDisplayMeta).setTransformationInterpolationDuration(3)
        playerRoll[player] = 0f
        playerState[player] = PlaneState.LANDED
        takeoffCounter[player] = 0
        playerThrottle[player] = 0f
        if (playerVehicleEntity[player] === entity) {
            VehicleCameraDistance.apply(player, hitbox, seatOffsets.firstOrNull() ?: Vec.ZERO)
        }
    }

    override fun onExit(player: Player) {
        val entity = playerVehicleEntity[player]
        val state = playerState[player]
        if (entity != null &&
            (state == PlaneState.FLYING || state == PlaneState.TAKING_OFF) &&
            entity !in destroyingEntities &&
            !Vehicle.isForcedExit(player)
        ) {
            val instance = entity.instance ?: player.instance
            if (instance != null &&
                !hitbox.checkGroundCollision(
                    instance,
                    entity.position,
                    entity.position.yaw,
                    entity.position.pitch,
                    playerRoll[player] ?: 0f,
                )
            ) {
                beginDive(entity, playerThrottle[player] ?: minAirThrottle, playerRoll[player] ?: 0f)
            }
        }
        try {
            super.onExit(player)
        } finally {
            if (entity != null) {
                (entity.entityMeta as ItemDisplayMeta).leftRotation = setRoll(0f)
            }
            playerRoll.remove(player)
            playerState.remove(player)
            takeoffCounter.remove(player)
            playerThrottle.remove(player)
            playerBombFireHeld.remove(player)
            VehicleCameraDistance.restore(player)
        }
    }

    override fun prepareForShutdown(entity: Entity) {
        entityDives.remove(entity)
        playerVehicleEntity.entries
            .filter { it.value === entity }
            .forEach { (player, _) ->
                playerState[player] = PlaneState.LANDED
                playerThrottle[player] = 0f
                playerRoll[player] = 0f
            }
        (entity.entityMeta as ItemDisplayMeta).leftRotation = setRoll(0f)
        super.prepareForShutdown(entity)
    }

    override fun cleanupRuntime(entity: Entity) {
        lastBombFireTime.remove(entity)
        entityDives.remove(entity)
        destroyingEntities.remove(entity)
    }

    override fun destroy(
        entity: Entity,
        attacker: Player?,
        weapon: Component?,
    ) {
        val instance = entity.instance
        val position = entity.position

        cleanupRuntime(entity)
        destroyingEntities.add(entity)
        try {
            super.destroy(entity, attacker, weapon)
        } finally {
            destroyingEntities.remove(entity)
        }

        if (instance != null) {
            Explosion(
                instance = instance,
                pos = position,
                radius = 5,
                fire = 0.5,
                damage = explosionDamage,
                source = attacker,
                weapon = weapon,
            )
        }
    }

    override fun onUnoccupiedTick(entity: Entity) {
        val dive = entityDives[entity] ?: return
        val instance = entity.instance ?: return
        val position = entity.position

        dive.pitch = approach(dive.pitch, divePitch, divePitchSpeed)
        dive.speed = nextDiveSpeed(dive.speed)
        val nextPosition = position.withPitch(dive.pitch)
        val target = nextPosition.add(nextPosition.direction().mul(dive.speed)).withPitch(dive.pitch)
        val impact = firstDiveCollision(instance, position, target, dive.roll)
        if (impact != null) {
            entity.teleport(impact)
            destroy(entity)
            return
        }

        entity.teleport(target)
        (entity.entityMeta as ItemDisplayMeta).leftRotation = setRoll(dive.roll / 55)
        updatePassengerSeats(entity)
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
            throttle =
                nextThrottle(
                    throttle,
                    inputEvent?.isHoldingForwardKey == true,
                    inputEvent?.isHoldingBackwardKey == true,
                )
        }
        playerThrottle[player] = throttle

        if (state == PlaneState.FLYING || state == PlaneState.TAKING_OFF) {
            val targetYaw = player.position.yaw
            val yawDelta = angleDifference(entity.position.yaw, targetYaw)
            val targetRoll = yawDelta.coerceIn(-45f, 45f)
            roll = approach(roll, targetRoll, 1.5f)
            playerRoll[player] = roll

            val yaw = approachAngle(entity.position.yaw, targetYaw, 45f * turnSpeed)
            entity.setView(yaw, player.position.pitch.coerceIn(-60f, 60f))
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
            entity.teleport(position.add(direction.mul(throttleSpeed)))
        }

        val meta = entity.entityMeta as ItemDisplayMeta
        meta.leftRotation = setRoll((playerRoll[player] ?: 0f) / 55)

        // Guns fire while held; bombs release only when the fire key is first pressed.
        val canFire = state == PlaneState.FLYING || state == PlaneState.TAKING_OFF
        val isHoldingFireKey = inputEvent?.isHoldingJumpKey == true
        if (canFire && isHoldingFireKey) {
            fireGuns(player)
            if (bomb != null && isBombRelease(isHoldingFireKey, playerBombFireHeld[player] == true)) {
                fireBomb(player)
            }
        }
        playerBombFireHeld[player] = canFire && isHoldingFireKey

        super.onTick(player)
    }

    override fun hitboxRoll(entity: Entity): Float {
        val pilot = playerVehicleEntity.entries.firstOrNull { it.value === entity }?.key ?: return 0f
        return playerRoll[pilot] ?: 0f
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

    internal fun nextDiveSpeed(current: Double): Double = (current + diveAcceleration).coerceAtMost(maxDiveSpeed)

    private fun beginDive(
        entity: Entity,
        throttle: Float,
        roll: Float,
    ) {
        val initialSpeed = (speed * throttle / maxThrottle).coerceAtLeast(speed * minAirThrottle / maxThrottle)
        entityDives[entity] = DiveState(initialSpeed.coerceAtMost(maxDiveSpeed), entity.position.pitch, roll)
    }

    private fun firstDiveCollision(
        instance: Instance,
        from: Pos,
        to: Pos,
        roll: Float,
    ): Pos? {
        val movement = to.asVec().sub(from)
        val samples = ceil(movement.length() / DIVE_COLLISION_SAMPLE_SPACING).toInt().coerceIn(1, MAX_DIVE_COLLISION_SAMPLES)
        for (sample in 1..samples) {
            val factor = sample.toDouble() / samples
            val position =
                Pos(
                    from.x + movement.x * factor,
                    from.y + movement.y * factor,
                    from.z + movement.z * factor,
                    to.yaw,
                    from.pitch + (to.pitch - from.pitch) * factor.toFloat(),
                )
            if (hitbox.checkGroundCollision(instance, position, position.yaw, position.pitch, roll)) return position
        }
        return null
    }

    internal fun nextThrottle(
        current: Float,
        isAccelerating: Boolean,
        isDecelerating: Boolean,
    ): Float {
        val next =
            when {
                isAccelerating && !isDecelerating -> current + throttleStep
                isDecelerating && !isAccelerating -> current - throttleStep
                else -> current - throttleDecay
            }
        return next.coerceIn(minAirThrottle, maxThrottle)
    }

    internal fun approachAngle(
        current: Float,
        target: Float,
        maxStep: Float,
    ): Float {
        val delta = angleDifference(current, target)
        return current + delta.coerceIn(-maxStep, maxStep)
    }

    private fun angleDifference(
        current: Float,
        target: Float,
    ): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    // fires all weapons from this plane
    private fun fireGuns(player: Player) {
        if (weapons.isEmpty()) return

        val entity = playerVehicleEntity[player] ?: return
        if ((getAmmo(entity) ?: 0) <= 0) {
            reloadAmmoIfEmpty(player, entity)
            return
        }

        val position = entity.position
        val roll = playerRoll[player] ?: 0f

        for ((_, mount) in weapons.withIndex()) {
            // fire from each fire point
            for (localPoint in mount.firePoints) {
                // local point to world space
                val worldOffset = rotatePoint(localPoint, position.yaw, position.pitch, roll)
                // Preserve the body view explicitly: Pos.add only represents the muzzle position,
                // while the ray and tracer must follow the plane's current aim.
                val firePos = position.add(worldOffset.x, worldOffset.y, worldOffset.z).withView(position.yaw, position.pitch)

                // fire the gun
                if ((getAmmo(entity) ?: 0) <= 0) return

                if (mount.gun.fire(
                        player,
                        firePos,
                        ignoreCooldown = true,
                        ignoreAmmo = true,
                        lagCompensate = false,
                    )
                ) {
                    consumeAmmo(entity)
                }
            }
        }
    }

    private fun fireBomb(player: Player) {
        val bomb = bomb ?: return
        val entity = playerVehicleEntity[player] ?: return
        val now = System.currentTimeMillis()
        if (now - (lastBombFireTime[entity] ?: 0L) < bomb.fireCooldown) return

        if ((getAmmo(entity) ?: 0) <= 0) {
            reloadAmmoIfEmpty(player, entity)
            return
        }

        val instance = entity.instance ?: return
        val position = entity.position
        val releaseOffset = rotatePoint(bomb.releaseOffset, position.yaw, position.pitch, playerRoll[player] ?: 0f)
        val releasePos = position.add(releaseOffset.x, releaseOffset.y, releaseOffset.z)
        val ignoredEntities =
            buildSet<Entity> {
                add(entity)
                add(player)
                addAll(entityPassengers[entity].orEmpty())
            }
        val obstruction =
            firstProjectileImpact(
                Ray(position, releasePos.asVec().sub(position)),
                instance,
                ignoredEntities,
            )

        if (obstruction != null) {
            Explosion.bypassingDamageImmunity(
                instance = instance,
                pos = obstruction.point.asPos(),
                radius = bomb.projectileExplosionRadius,
                fire = bomb.projectileExplosionFire,
                damage = bomb.projectileExplosionDamage,
                source = player,
                weapon = bomb.projectileName,
                ammoType = ammo.ammoType,
            )
        } else {
            Projectile.bypassingDamageImmunity(
                instance = instance,
                pos = releasePos,
                model = bomb.projectileModel,
                direction = Vec(0.0, -1.0, 0.0),
                speed = bomb.projectileSpeed,
                explosionRadius = bomb.projectileExplosionRadius,
                explosionFire = bomb.projectileExplosionFire,
                explosionDamage = bomb.projectileExplosionDamage,
                source = player,
                weapon = bomb.projectileName,
                ignoredEntities = ignoredEntities,
                ammoType = ammo.ammoType,
                trailParticle = bomb.projectileTrailParticle,
                trailSpacing = bomb.projectileTrailSpacing,
                trailMaxParticles = bomb.projectileTrailMaxParticles,
                maxRange = bomb.projectileMaxRange,
            )
        }

        consumeAmmo(entity)
        lastBombFireTime[entity] = now
    }

    companion object {
        var playerRoll = hashMapOf<Player, Float>()
        var playerState = hashMapOf<Player, PlaneState>()
        var takeoffCounter = hashMapOf<Player, Int>()
        var playerThrottle = hashMapOf<Player, Float>()
        var playerBombFireHeld = hashMapOf<Player, Boolean>()
        var lastBombFireTime = hashMapOf<Entity, Long>()
        private val entityDives = HashMap<Entity, DiveState>()
        private val destroyingEntities = HashSet<Entity>()

        internal fun shutdownRuntimeState() {
            playerRoll.clear()
            playerState.clear()
            takeoffCounter.clear()
            playerThrottle.clear()
            playerBombFireHeld.clear()
            lastBombFireTime.clear()
            entityDives.clear()
            destroyingEntities.clear()
        }

        private const val DIVE_COLLISION_SAMPLE_SPACING = 0.25
        private const val MAX_DIVE_COLLISION_SAMPLES = 128
    }
}

internal fun isBombRelease(
    isHoldingFireKey: Boolean,
    wasHoldingFireKey: Boolean,
): Boolean = isHoldingFireKey && !wasHoldingFireKey
