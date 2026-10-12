package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.particle.Particle

/**
 * Slowly movable artillery operated while standing behind the carriage.
 * Move forward/back to push/pull, left/right to steer, look to aim, jump to fire,
 * and sneak to release control. Barrel models share the
 * carriage origin; [barrelTipOffset] is measured in world units from that origin.
 */
class Cannon(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    bodyModel: String = "$model-body",
    val barrelModel: String = "$model-barrel",
    scale: Double,
    hitbox: Hitbox,
    health: Health,
    placeTime: Long = 1000,
    val traverseSpeed: Float = 3f,
    val minPitch: Float = -60f,
    val maxPitch: Float = 10f,
    val projectileModel: String = "$model-shell",
    val projectileName: Component = Component.text("Cannon"),
    val projectileSpeed: Double = 4.0,
    val projectileExplosionRadius: Int = 4,
    val projectileExplosionFire: Double = 0.1,
    val projectileExplosionDamage: Float = 20f,
    override val ammo: Ammo,
    override val maxAmmo: Int = 1,
    val barrelTipOffset: Vec = Vec(0.0, 0.0, 5.0),
    override val reloadTime: Long = 20000,
    seats: List<VehicleSeat> =
        listOf(
            VehicleSeat(
                "operator",
                "Artillery operator",
                VehicleSeatRole.OPERATOR,
                Vec(
                    0.0,
                    -hitbox.getGroundOffset(),
                    (hitbox.parts.minOfOrNull { it.offset.z - it.size.z } ?: 0.0) - 0.5,
                ),
                standing = true,
            ),
        ),
    invisibleWhileRiding: Boolean = false,
    invulnerableWhileRiding: Boolean = false,
    val projectileTrailParticle: Particle? = Particle.ELECTRIC_SPARK,
    val projectileTrailSpacing: Double = 1.0,
    val projectileTrailMaxParticles: Int = 96,
    val projectileMaxRange: Double = 128.0,
    moveSpeed: Double = 0.04,
    turnSpeed: Float = 1f,
    animatedParts: List<AnimatedPart> = emptyList(),
    val maxYaw: Float = 180f,
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
) : FieldPiece(
        name = name,
        itemName = itemName,
        itemLore = itemLore,
        itemModel = itemModel,
        model = bodyModel,
        scale = scale,
        hitbox = hitbox,
        health = health,
        placeTime = placeTime,
        seats = seats,
        invisibleWhileRiding = invisibleWhileRiding,
        invulnerableWhileRiding = invulnerableWhileRiding,
        moveSpeed = moveSpeed,
        turnSpeed = turnSpeed,
        animatedParts = animatedParts,
        collisionHitbox = collisionHitbox,
    ),
    ArmedVehicle {
    private data class CannonRuntime(
        val barrel: Entity,
    )

    private val runtimes = HashMap<Entity, CannonRuntime>()
    private val aiming = FieldPieceAiming(traverseSpeed, maxYaw, minPitch, maxPitch)

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

    init {
        require(traverseSpeed.isFinite() && traverseSpeed > 0f) { "Cannon traverseSpeed must be positive and finite" }
        require(maxYaw.isFinite() && maxYaw in 0f..180f) { "Cannon maxYaw must be within 0 to 180 degrees" }
        require(minPitch.isFinite() && maxPitch.isFinite() && minPitch >= -90f && maxPitch <= 90f && minPitch <= maxPitch) {
            "Cannon pitch limits must be ordered within -90 to 90 degrees"
        }
        require(reloadTime >= 0) { "Cannon reloadTime must not be negative" }
        require(maxAmmo > 0) { "Cannon maxAmmo must be greater than zero" }
        require(projectileSpeed > 0.0 && projectileSpeed.isFinite()) {
            "Cannon projectileSpeed must be positive and finite"
        }
        require(projectileTrailSpacing > 0.0 && projectileTrailSpacing.isFinite()) {
            "Cannon projectileTrailSpacing must be positive and finite"
        }
        require(projectileTrailMaxParticles >= 2) { "Cannon projectileTrailMaxParticles must be at least two" }
        require(projectileMaxRange > 0.0 && projectileMaxRange.isFinite()) {
            "Cannon projectileMaxRange must be positive and finite"
        }
    }

    override fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity {
        // spawn the body via the normal vehicle spawn
        val body = super.spawn(instance, pos)
        val barrel = VehicleDisplayEntity(body)
        runtimes[body] = CannonRuntime(barrel)
        try {
            barrel.spawnWeaponDisplay(
                body.instance,
                body.position.withView(body.position.yaw, 0f.coerceIn(minPitch, maxPitch)),
                barrelModel,
                scale,
            )
            return body
        } catch (failure: Throwable) {
            runCatching { removeRuntimeEntity(body) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    override fun onTick(player: Player) {
        super.onTick(player)
        val entity = driverEntity(player) ?: return
        val runtime = runtimes[entity] ?: return
        val barrel = runtime.barrel
        val pos = entity.position
        val barrelPos = aiming.step(pos, barrel.position, player.position)
        barrel.teleport(barrelPos)

        // fire
        val inputEvent = KeyPressListener.playerInputEvent[player]
        if (inputEvent?.isHoldingJumpKey == true) {
            fire(player, entity, pos, barrelPos.yaw, barrelPos.pitch)
        }
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
            runtimes.remove(entity)?.let { removeWeaponDisplays(it.barrel) }
        } finally {
            super.cleanupRuntime(entity)
        }
    }
}
