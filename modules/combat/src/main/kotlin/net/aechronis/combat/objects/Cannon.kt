package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.rotatePoint
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.title.Title
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.particle.Particle
import kotlin.math.floor

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
    val fireCooldown: Long = 20000,
    seatOffsets: List<Vec> =
        listOf(
            Vec(0.0, -hitbox.getGroundOffset(), (hitbox.parts.minOfOrNull { it.offset.z - it.size.z } ?: 0.0) - 0.5),
        ),
    invisibleWhileRiding: Boolean = false,
    invulnerableWhileRiding: Boolean = false,
    val projectileTrailParticle: Particle? = Particle.ELECTRIC_SPARK,
    val projectileTrailSpacing: Double = 1.0,
    val projectileTrailMaxParticles: Int = 96,
    val projectileMaxRange: Double = 128.0,
    val moveSpeed: Double = 0.04,
    val turnSpeed: Float = 1f,
    animatedParts: List<AnimatedPart> = emptyList(),
) : Vehicle(
        name,
        itemName,
        itemLore,
        itemModel,
        bodyModel,
        scale,
        hitbox,
        health,
        placeTime,
        seatOffsets,
        invisibleWhileRiding,
        invulnerableWhileRiding,
        animatedParts,
    ),
    ArmedVehicle {
    override val standingDriver: Boolean = true

    private data class CannonRuntime(
        val barrel: Entity,
        var lastFireTime: Long? = null,
    )

    private val runtimes = HashMap<Entity, CannonRuntime>()

    init {
        require(moveSpeed.isFinite() && moveSpeed > 0.0 && moveSpeed <= 0.1) { "Cannon moveSpeed must be in (0, 0.1] blocks per tick" }
        require(turnSpeed.isFinite() && turnSpeed > 0f && turnSpeed <= 3f) { "Cannon turnSpeed must be in (0, 3] degrees per tick" }
        require(traverseSpeed.isFinite() && traverseSpeed > 0f) { "Cannon traverseSpeed must be positive and finite" }
        require(minPitch.isFinite() && maxPitch.isFinite() && minPitch >= -90f && maxPitch <= 90f && minPitch <= maxPitch) {
            "Cannon pitch limits must be ordered within -90 to 90 degrees"
        }
        require(fireCooldown >= 0) { "Cannon fireCooldown must not be negative" }
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

        val barrel = Entity(EntityType.ITEM_DISPLAY)
        barrel.setInstance(body.instance, body.position.withView(body.position.yaw, 0f.coerceIn(minPitch, maxPitch)))

        val barrelMeta = barrel.entityMeta as ItemDisplayMeta
        barrelMeta.itemStack = ItemStack.of(Material.BONE).withItemModel(barrelModel)
        barrelMeta.posRotInterpolationDuration = 3
        barrelMeta.scale = Vec(scale)
        barrelMeta.isHasNoGravity = true

        barrel.spawn()

        runtimes[body] = CannonRuntime(barrel)

        return body
    }

    override fun onTick(player: Player) {
        if (KeyPressListener.playerInputEvent[player]?.isHoldingShiftKey == true) {
            onExit(player)
            return
        }
        driverEntity(player)?.let { move(player, it) }
        super.onTick(player)
        val entity = driverEntity(player) ?: return
        val runtime = runtimes[entity] ?: return
        val barrel = runtime.barrel
        val pos = entity.position
        val newYaw = approachAngle(barrel.position.yaw, player.position.yaw, traverseSpeed)
        val targetPitch = player.position.pitch.coerceIn(minPitch, maxPitch)
        val newPitch = barrel.position.pitch + (targetPitch - barrel.position.pitch).coerceIn(-traverseSpeed, traverseSpeed)
        barrel.teleport(pos.withView(newYaw, newPitch))

        // fire
        val inputEvent = KeyPressListener.playerInputEvent[player]
        if (inputEvent?.isHoldingJumpKey == true) {
            fire(player, entity, pos, newYaw, newPitch)
        }

        // progress bar
        val last = runtime.lastFireTime
        if (last != null) {
            val elapsed = System.currentTimeMillis() - last
            if (elapsed < fireCooldown) {
                val progress = (elapsed.toDouble() / fireCooldown.toDouble()).coerceIn(0.0, 1.0)
                player.showTitle(
                    Title.title(
                        Component.empty(),
                        Message.progressBar(progress).shadowColor(ShadowColor.none()),
                        0,
                        3,
                        10,
                    ),
                )
            }
        }
    }

    private fun move(
        player: Player,
        entity: Entity,
    ) {
        val input = KeyPressListener.playerInputEvent[player] ?: return
        val forward = (if (input.isHoldingForwardKey) 1 else 0) - (if (input.isHoldingBackwardKey) 1 else 0)
        val turn = (if (input.isHoldingRightKey) 1 else 0) - (if (input.isHoldingLeftKey) 1 else 0)
        if (forward == 0 && turn == 0) return
        val instance = entity.instance ?: return
        val position = entity.position
        val yaw = position.yaw + turn * turnSpeed
        val movement = rotatePoint(Vec(0.0, 0.0, forward * moveSpeed), yaw, 0f, 0f)
        val candidate = position.add(movement).withView(yaw, 0f)
        val operator = candidate.add(rotatePoint(seatOffsets.firstOrNull() ?: Vec.ZERO, yaw, 0f, 0f))
        val operatorBox =
            Hitbox(
                listOf(
                    HitboxPart(
                        Vec(0.0, player.boundingBox.height() / 2, 0.0),
                        Vec(player.boundingBox.width() / 2, player.boundingBox.height() / 2, player.boundingBox.depth() / 2),
                    ),
                ),
            )
        // Both the carriage and operator need solid footing; do not roll over a ledge.
        val corners = hitbox.getWorldCorners(candidate, yaw, 0f, 0f).flatten()
        val groundY = candidate.y - hitbox.getGroundOffset()
        if (corners.any { !hasFooting(instance, it.x, groundY, it.z) }) return
        if (!hasFooting(instance, operator.x, operator.y, operator.z)) return
        if (blocked(instance, hitbox, candidate) || blocked(instance, operatorBox, operator.withYaw(0f))) return
        if (VehicleRegistry.all().any { runtime ->
                runtime.entity !== entity &&
                    runtime.entity.instance === instance &&
                    (overlaps(hitbox, candidate, runtime) || overlaps(operatorBox, operator.withYaw(0f), runtime))
            }
        ) {
            return
        }
        entity.teleport(candidate)
    }

    private fun hasFooting(
        instance: Instance,
        x: Double,
        y: Double,
        z: Double,
    ): Boolean {
        val point = Pos(x, y - 0.01, z)
        return instance.isChunkLoaded(point) && instance.getBlock(point).isSolid
    }

    private fun overlaps(
        box: Hitbox,
        position: Pos,
        other: VehicleRuntime,
    ): Boolean =
        box.intersects(
            other.vehicle.hitbox,
            position,
            position.yaw,
            0f,
            0f,
            other.entity.position,
            other.entity.position.yaw,
            other.entity.position.pitch,
            other.vehicle.hitboxRoll(other.entity),
        )

    private fun blocked(
        instance: Instance,
        box: Hitbox,
        position: Pos,
    ): Boolean {
        val corners = box.getWorldCorners(position, position.yaw, 0f, 0f).flatten()
        if (corners.isEmpty()) return false
        val blockBox = Hitbox(listOf(HitboxPart(Vec.ZERO, Vec(0.499))))
        for (x in floor(corners.minOf { it.x }).toInt()..floor(corners.maxOf { it.x }).toInt()) {
            for (y in floor(corners.minOf { it.y }).toInt()..floor(corners.maxOf { it.y }).toInt()) {
                for (z in floor(corners.minOf { it.z }).toInt()..floor(corners.maxOf { it.z }).toInt()) {
                    val blockPos = Pos(x + 0.5, y + 0.5, z + 0.5)
                    if (!instance.isChunkLoaded(blockPos)) return true
                    if (instance.getBlock(x, y, z).isSolid &&
                        box.intersects(blockBox, position, position.yaw, 0f, 0f, blockPos, 0f, 0f, 0f)
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun fire(
        player: Player,
        body: Entity,
        barrelPos: Pos,
        yaw: Float,
        pitch: Float,
    ) {
        val now = System.currentTimeMillis()
        val runtime = runtimes[body] ?: return
        val last = runtime.lastFireTime
        if (last != null && now - last < fireCooldown) return

        if ((getAmmo(body) ?: 0) <= 0) {
            reloadAmmoIfEmpty(player, body)
            return
        }

        val instance = body.instance ?: return

        val tip = rotatePoint(barrelTipOffset, yaw, pitch, 0f)
        val muzzle = barrelPos.add(tip.x, tip.y, tip.z)
        val direction = muzzle.withView(yaw, pitch).direction()
        val ignoredEntities =
            buildSet<Entity> {
                add(body)
                add(player)
                addAll(VehicleRegistry.passengers(body).map { it.player })
            }
        val obstruction =
            firstProjectileImpact(
                Ray(barrelPos, muzzle.asVec().sub(barrelPos)),
                instance,
                ignoredEntities,
            )

        if (obstruction != null) {
            Explosion.bypassingDamageImmunity(
                instance = instance,
                pos = obstruction.point.asPos(),
                radius = projectileExplosionRadius,
                fire = projectileExplosionFire,
                damage = projectileExplosionDamage,
                source = player,
                weapon = projectileName,
                ammoType = ammo.ammoType,
            )
        } else {
            Projectile.bypassingDamageImmunity(
                instance = instance,
                pos = muzzle,
                model = projectileModel,
                direction = direction,
                speed = projectileSpeed,
                explosionRadius = projectileExplosionRadius,
                explosionFire = projectileExplosionFire,
                explosionDamage = projectileExplosionDamage,
                source = player,
                weapon = projectileName,
                ignoredEntities = ignoredEntities,
                ammoType = ammo.ammoType,
                trailParticle = projectileTrailParticle,
                trailSpacing = projectileTrailSpacing,
                trailMaxParticles = projectileTrailMaxParticles,
                maxRange = projectileMaxRange,
            )
        }

        consumeAmmo(body)
        runtime.lastFireTime = now
    }

    override fun cleanupRuntime(entity: Entity) {
        val runtime = runtimes.remove(entity) ?: return
        runtime.barrel.remove()
    }

    override fun destroy(
        entity: Entity,
        attacker: Player?,
        weapon: Component?,
    ) {
        cleanupRuntime(entity)
        super.destroy(entity, attacker, weapon)
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
