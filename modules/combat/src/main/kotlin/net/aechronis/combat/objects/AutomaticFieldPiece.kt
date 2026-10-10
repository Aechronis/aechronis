package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.rotatePoint
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance

/**
 * A pushable gun carriage: look to aim and hold jump to fire at [gun]'s configured cadence.
 * Uses the gun's damage, spread, sound and tracers, with a separate magazine per carriage.
 * [barrelPivotOffset] places the barrel's model origin relative to the carriage.
 * [barrelTipOffset] is measured from that pivot in world units before aiming rotation.
 */
class AutomaticFieldPiece(
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
    val gun: Gun,
    placeTime: Long = 1000,
    val traverseSpeed: Float = 3f,
    val minPitch: Float = -60f,
    val maxPitch: Float = 10f,
    override val maxAmmo: Int = gun.maxAmmo,
    val barrelTipOffset: Vec = Vec(0.0, 0.0, 5.0),
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
    moveSpeed: Double = 0.04,
    turnSpeed: Float = 1f,
    animatedParts: List<AnimatedPart> = emptyList(),
    val maxYaw: Float = 180f,
    val barrelPivotOffset: Vec = Vec.ZERO,
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
    override val ammo: Ammo get() = gun.ammo
    override val reloadTime: Long get() = gun.reloadTime

    private data class Runtime(
        val barrel: Entity,
        var lastFireTime: Long? = null,
    )

    private val runtimes = HashMap<Entity, Runtime>()
    private val aiming = FieldPieceAiming(traverseSpeed, maxYaw, minPitch, maxPitch)

    init {
        require(reloadTime >= 0) { "AutomaticFieldPiece reloadTime must not be negative" }
        require(traverseSpeed.isFinite() && traverseSpeed > 0f) { "AutomaticFieldPiece traverseSpeed must be positive and finite" }
        require(maxYaw.isFinite() && maxYaw in 0f..180f) { "AutomaticFieldPiece maxYaw must be within 0 to 180 degrees" }
        require(minPitch.isFinite() && maxPitch.isFinite() && minPitch >= -90f && maxPitch <= 90f && minPitch <= maxPitch) {
            "AutomaticFieldPiece pitch limits must be ordered within -90 to 90 degrees"
        }
        require(maxAmmo > 0) { "AutomaticFieldPiece maxAmmo must be greater than zero" }
        require(barrelTipOffset.x.isFinite() && barrelTipOffset.y.isFinite() && barrelTipOffset.z.isFinite()) {
            "AutomaticFieldPiece barrelTipOffset must be finite"
        }
        require(barrelPivotOffset.x.isFinite() && barrelPivotOffset.y.isFinite() && barrelPivotOffset.z.isFinite()) {
            "AutomaticFieldPiece barrelPivotOffset must be finite"
        }
    }

    override fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity {
        val body = super.spawn(instance, pos)
        val barrel = VehicleDisplayEntity(body)
        runtimes[body] = Runtime(barrel)
        try {
            barrel.spawnWeaponDisplay(
                instance,
                barrelOrigin(body).withView(body.position.yaw, 0f.coerceIn(minPitch, maxPitch)),
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
        val body = driverEntity(player) ?: return
        val runtime = runtimes[body] ?: return
        val barrel = runtime.barrel
        val barrelPos = aiming.step(barrelOrigin(body), barrel.position, player.position)
        barrel.teleport(barrelPos)

        if (KeyPressListener.playerInputEvent[player]?.isHoldingJumpKey == true) {
            fire(player, body, runtime, barrelPos)
        }
    }

    private fun barrelOrigin(body: Entity): Pos = body.position.add(rotatePoint(barrelPivotOffset, body.position.yaw, 0f, 0f))

    private fun fire(
        player: Player,
        body: Entity,
        runtime: Runtime,
        barrelPos: Pos,
    ) {
        val now = System.currentTimeMillis()
        val last = runtime.lastFireTime
        if (last != null && now - last < gun.cooldown) return
        if (!hasReadyAmmo(player, body)) return
        val instance = body.instance ?: return
        val tip = rotatePoint(barrelTipOffset, barrelPos.yaw, barrelPos.pitch, 0f)
        val muzzle = barrelPos.add(tip).withView(barrelPos.yaw, barrelPos.pitch)
        val ignoredEntities =
            buildSet<Entity> {
                add(body)
                add(player)
                addAll(VehicleRegistry.ridesOf(body).map { it.player })
            }
        val barrelRay = Ray(barrelPos, tip)
        val obstruction = firstProjectileImpact(barrelRay, instance, ignoredEntities)
        // A long barrel must not fire through cover between its pivot and muzzle.
        // Start just inside the obstruction so the gun's normal hit resolution hits it.
        val firePos =
            obstruction
                ?.point
                ?.add(barrelRay.direction.mul(1.0e-6))
                ?.asPos()
                ?.withView(barrelPos.yaw, barrelPos.pitch)
                ?: muzzle
        if (gun.fire(
                player,
                firePos,
                ignoreCooldown = true,
                ignoreAmmo = true,
                lagCompensate = false,
                ignoredEntities = ignoredEntities,
            )
        ) {
            consumeAmmo(body)
            runtime.lastFireTime = now
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
