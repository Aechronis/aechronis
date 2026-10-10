package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.rotatePoint
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.particle.Particle

class Tank(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    bodyModel: String = "$model-body",
    val turretModel: String = "$model-turret",
    val barrelModel: String = "$model-barrel",
    scale: Double,
    hitbox: Hitbox,
    health: Health,
    placeTime: Long = 1000,
    maxSpeed: Float = 0.2f,
    acceleration: Float = 0.01f,
    braking: Float = 0.02f,
    friction: Float = 0.95f,
    turnSpeed: Float = 2.0f,
    maxClimbHeight: Float = 1.2f,
    val turretTraverseSpeed: Float = 3f,
    val projectileModel: String = "$model-shell",
    val projectileName: Component = Component.text("Tank cannon"),
    val projectileSpeed: Double = 4.0,
    val projectileExplosionRadius: Int = 4,
    val projectileExplosionFire: Double = 0.1,
    val projectileExplosionDamage: Float = 20f,
    override val ammo: Ammo,
    override val maxAmmo: Int,
    val barrelTipOffset: Vec = Vec(0.0, 0.0, 5.0),
    override val reloadTime: Long = 20000,
    seats: List<VehicleSeat> =
        listOf(
            VehicleSeat("driver", "Driver", VehicleSeatRole.DRIVER, Vec(0.0, 0.0, 1.0)),
            VehicleSeat("gunner", "Main gunner", VehicleSeatRole.GUNNER, Vec(0.0, 0.0, -0.5), weaponId = "main"),
        ),
    invisibleWhileRiding: Boolean = true,
    invulnerableWhileRiding: Boolean = true,
    val projectileTrailParticle: Particle? = Particle.ELECTRIC_SPARK,
    val projectileTrailSpacing: Double = 1.0,
    val projectileTrailMaxParticles: Int = 96,
    val projectileMaxRange: Double = 128.0,
    animatedParts: List<AnimatedPart> = emptyList(),
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
) : Car(
        name,
        itemName,
        itemLore,
        itemModel,
        bodyModel,
        scale,
        hitbox,
        health,
        placeTime,
        maxSpeed,
        acceleration,
        braking,
        friction,
        turnSpeed,
        maxClimbHeight,
        seats,
        invisibleWhileRiding,
        invulnerableWhileRiding,
        animatedParts,
        collisionHitbox,
    ),
    ArmedVehicle {
    init {
        require(this.seats.count { it.role == VehicleSeatRole.GUNNER } == 1) { "Tanks need one main gunner" }
        require(reloadTime >= 0) { "Tank reloadTime must not be negative" }
        require(maxAmmo > 0) { "Tank maxAmmo must be greater than zero" }
        require(projectileSpeed > 0.0 && projectileSpeed.isFinite()) {
            "Tank projectileSpeed must be positive and finite"
        }
        require(projectileTrailSpacing > 0.0 && projectileTrailSpacing.isFinite()) {
            "Tank projectileTrailSpacing must be positive and finite"
        }
        require(projectileTrailMaxParticles >= 2) { "Tank projectileTrailMaxParticles must be at least two" }
        require(projectileMaxRange > 0.0 && projectileMaxRange.isFinite()) {
            "Tank projectileMaxRange must be positive and finite"
        }
    }

    private class Runtime(
        val turret: Entity,
        val barrel: Entity,
        var yaw: Float = 0f,
        var pitch: Float = 0f,
    )

    private val runtimes = HashMap<Entity, Runtime>()

    private val projectileLauncher =
        VehicleProjectileLauncher(
            barrelTipOffset = barrelTipOffset,
            model = projectileModel,
            name = projectileName,
            speed = projectileSpeed,
            explosionRadius = projectileExplosionRadius,
            explosionFire = projectileExplosionFire,
            explosionDamage = projectileExplosionDamage,
            ammoType = ammo.ammoType,
            trailParticle = projectileTrailParticle,
            trailSpacing = projectileTrailSpacing,
            trailMaxParticles = projectileTrailMaxParticles,
            maxRange = projectileMaxRange,
        )

    override fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity {
        // spawn the body via the normal vehicle spawn
        val body = super.spawn(instance, pos)

        val turret = VehicleDisplayEntity(body)
        val barrel = VehicleDisplayEntity(body)
        runtimes[body] = Runtime(turret, barrel)
        try {
            turret.spawnWeaponDisplay(body.instance, body.position, turretModel, scale)
            barrel.spawnWeaponDisplay(body.instance, turret.position, barrelModel, scale)

            return body
        } catch (failure: Throwable) {
            runCatching { removeRuntimeEntity(body) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    override fun onTick(player: Player) {
        super.onTick(player)
        driverEntity(player)?.let(::updateTurret)
    }

    override fun onUnoccupiedTick(entity: Entity) {
        super.onUnoccupiedTick(entity)
        updateTurret(entity)
    }

    override fun onGunnerTick(player: Player) {
        val ride = VehicleRegistry.gunner(player)?.takeIf { it.vehicle === this } ?: return
        if (KeyPressListener.playerInputEvent[player]?.isHoldingShiftKey == true) {
            super.onGunnerTick(player)
            return
        }
        val entity = ride.entity
        val runtime = runtimes[entity] ?: return
        runtime.yaw = approachAngle(runtime.yaw, player.position.yaw - entity.position.yaw, turretTraverseSpeed)
        runtime.pitch = approachAngle(runtime.pitch, player.position.pitch.coerceIn(-25f, 5f), turretTraverseSpeed)
        updateTurret(entity)
        super.onGunnerTick(player)
        if (KeyPressListener.playerInputEvent[player]?.isHoldingJumpKey == true) {
            fire(player, entity, entity.position, entity.position.yaw + runtime.yaw, runtime.pitch)
        }
    }

    override fun getSeatWorldPos(
        entity: Entity,
        seatIndex: Int,
    ): Pos {
        val definition = seats[seatIndex]
        if (definition.role != VehicleSeatRole.GUNNER) return super.getSeatWorldPos(entity, seatIndex)
        // The crew position follows the turret; barrel elevation does not pitch the occupant.
        return entity.position.add(rotatePoint(definition.offset, entity.position.yaw + (runtimes[entity]?.yaw ?: 0f), 0f, 0f))
    }

    private fun updateTurret(entity: Entity) {
        val runtime = runtimes[entity] ?: return
        val position = entity.position
        val turretYaw = position.yaw + runtime.yaw
        runtime.turret.teleport(position.withView(turretYaw, 0f))
        runtime.barrel.teleport(position.withView(turretYaw, runtime.pitch))
    }

    private fun fire(
        player: Player,
        body: Entity,
        barrelPos: Pos,
        yaw: Float,
        pitch: Float,
    ) {
        if (!hasReadyAmmo(player, body)) return

        if (projectileLauncher.fire(player, body, barrelPos, yaw, pitch)) {
            consumeAmmo(body, reloadAfterShot = true)
        }
    }

    override fun cleanupRuntime(entity: Entity) {
        try {
            runtimes.remove(entity)?.let { runtime ->
                removeWeaponDisplays(runtime.turret, runtime.barrel)
            }
        } finally {
            super.cleanupRuntime(entity)
        }
    }

    // steps [current] toward [target] by at most [maxStep] degrees, takes the shortest way around
    private fun approachAngle(
        current: Float,
        target: Float,
        maxStep: Float,
    ): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return when {
            delta > maxStep -> current + maxStep
            delta < -maxStep -> current - maxStep
            else -> current + delta
        }
    }
}
