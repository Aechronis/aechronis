package net.aechronis.combat.objects

import net.aechronis.combat.utils.Particles
import net.aechronis.combat.utils.Ray
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.particle.Particle

class Projectile private constructor(
    val instance: Instance,
    val pos: Pos,
    val model: String,
    val direction: Vec,
    val speed: Double = 1.0,
    val explosionRadius: Int = 4,
    val explosionFire: Double = .33,
    val gravity: Double = 0.05,
    val explosionDamage: Float = 20f,
    val source: Player? = null,
    val weapon: Component? = null,
    val ammoType: AmmoTypes? = null,
    val fuseDeadlineMillis: Long? = null,
    val trailParticle: Particle? = null,
    val trailSpacing: Double = 1.0,
    val trailMaxParticles: Int = 96,
    val maxRange: Double? = null,
    private val bypassDamageImmunity: Boolean,
    private val ignoredEntities: Set<Entity>,
) {
    constructor(
        instance: Instance,
        pos: Pos,
        model: String,
        direction: Vec,
        speed: Double = 1.0,
        explosionRadius: Int = 4,
        explosionFire: Double = .33,
        gravity: Double = 0.05,
        explosionDamage: Float = 20f,
        source: Player? = null,
        weapon: Component? = null,
        ammoType: AmmoTypes? = null,
        fuseDeadlineMillis: Long? = null,
        trailParticle: Particle? = null,
        trailSpacing: Double = 1.0,
        trailMaxParticles: Int = 96,
        maxRange: Double? = null,
    ) : this(
        instance,
        pos,
        model,
        direction,
        speed,
        explosionRadius,
        explosionFire,
        gravity,
        explosionDamage,
        source,
        weapon,
        ammoType,
        fuseDeadlineMillis,
        trailParticle,
        trailSpacing,
        trailMaxParticles,
        maxRange,
        false,
        emptySet(),
    )

    private val entity: Entity
    private var velocity: Vec = direction.mul(speed)
    private var travelledDistance = 0.0
    var isActive = true

    init {
        require(speed.isFinite() && speed > 0.0) { "Projectile speed must be positive and finite" }
        require(gravity.isFinite()) { "Projectile gravity must be finite" }
        require(trailSpacing.isFinite() && trailSpacing > 0.0) { "Projectile trailSpacing must be positive and finite" }
        require(trailMaxParticles >= 2) { "Projectile trailMaxParticles must be at least two" }
        require(maxRange == null || (maxRange.isFinite() && maxRange > 0.0)) {
            "Projectile maxRange must be positive and finite when set"
        }
        val itemDisplay = Entity(EntityType.ITEM_DISPLAY)

        itemDisplay.setInstance(instance, pos.withDirection(velocity))

        val meta = itemDisplay.entityMeta as ItemDisplayMeta

        meta.itemStack = ItemStack.of(Material.BONE).withItemModel(model)

        meta.isHasNoGravity = true

        itemDisplay.spawn()

        this.entity = itemDisplay

        activeProjectiles.add(this)
    }

    fun onTick() {
        if (!isActive) return

        val currentPos = entity.position
        if (fuseDeadlineMillis != null && System.currentTimeMillis() >= fuseDeadlineMillis) {
            detonate(currentPos)
            return
        }

        // Accelerate downward so the projectile arcs over time, then limit this tick to its
        // remaining range. This keeps both collision and the visual trail on the same path.
        velocity = velocity.sub(0.0, gravity, 0.0)
        val remainingRange = maxRange?.let { it - travelledDistance }
        if (remainingRange != null && remainingRange <= 0.0) {
            expire()
            return
        }
        val movement =
            if (remainingRange != null && velocity.length() > remainingRange) {
                velocity.normalize().mul(remainingRange)
            } else {
                velocity
            }
        val nextPos = currentPos.add(movement)

        val impact = firstProjectileImpact(Ray(currentPos, movement), instance, ignoredEntities + listOfNotNull(source))
        val trailEnd = impact?.point?.asPos() ?: nextPos
        trailParticle?.let { particle ->
            Particles.particleLine(instance, particle, currentPos, trailEnd, trailSpacing, trailMaxParticles)
        }
        travelledDistance += currentPos.distance(trailEnd)

        if (impact != null) {
            detonate(trailEnd)
            return
        }
        if (remainingRange != null && travelledDistance >= maxRange) {
            expire()
            return
        }

        // chunk is loaded
        if (!instance.isChunkLoaded(nextPos)) {
            expire()
            return
        }

        // move the entity
        entity.teleport(nextPos.withDirection(movement))
    }

    private fun expire() {
        isActive = false
        entity.remove()
    }

    private fun detonate(pos: Pos) {
        if (bypassDamageImmunity) {
            Explosion.bypassingDamageImmunity(
                instance = instance,
                pos = pos,
                radius = explosionRadius,
                fire = explosionFire,
                damage = explosionDamage,
                source = source,
                weapon = weapon,
                ammoType = ammoType,
            )
        } else {
            Explosion(
                instance = instance,
                pos = pos,
                radius = explosionRadius,
                fire = explosionFire,
                damage = explosionDamage,
                source = source,
                weapon = weapon,
                ammoType = ammoType,
            )
        }
        isActive = false
        entity.remove()
    }

    companion object {
        val activeProjectiles: MutableList<Projectile> = mutableListOf()

        internal fun bypassingDamageImmunity(
            instance: Instance,
            pos: Pos,
            model: String,
            direction: Vec,
            speed: Double,
            explosionRadius: Int,
            explosionFire: Double,
            explosionDamage: Float,
            source: Player?,
            weapon: Component?,
            ignoredEntities: Set<Entity>,
            ammoType: AmmoTypes?,
            fuseDeadlineMillis: Long? = null,
            trailParticle: Particle? = null,
            trailSpacing: Double = 1.0,
            trailMaxParticles: Int = 96,
            maxRange: Double? = null,
        ): Projectile =
            Projectile(
                instance,
                pos,
                model,
                direction,
                speed,
                explosionRadius,
                explosionFire,
                0.05,
                explosionDamage,
                source,
                weapon,
                ammoType,
                fuseDeadlineMillis,
                trailParticle,
                trailSpacing,
                trailMaxParticles,
                maxRange,
                true,
                ignoredEntities,
            )
    }
}

internal data class ProjectileImpact(
    val t: Double,
    val point: Point,
)

internal fun firstProjectileImpact(
    ray: Ray,
    instance: Instance,
    ignoredEntities: Set<Entity> = emptySet(),
): ProjectileImpact? {
    val blockHit = ray.firstBlock(instance)
    val entityHit =
        ray.firstEntity(
            instance.entities
                .filterIsInstance<LivingEntity>()
                .filter { it !in ignoredEntities },
        )
    val vehicleHit = firstVehicleImpact(ray, instance, ignoredEntities)
    return selectProjectileImpact(blockHit, entityHit, vehicleHit)
}

private fun firstVehicleImpact(
    ray: Ray,
    instance: Instance,
    ignoredEntities: Set<Entity>,
): ProjectileImpact? {
    var closest: ProjectileImpact? = null

    for ((entity, vehicle) in Vehicle.entityVehicle) {
        if (entity.instance !== instance || entity in ignoredEntities) continue

        val position = entity.position
        val t =
            vehicle.hitbox.firstIntersection(
                ray.origin,
                ray.vector,
                position,
                position.yaw,
                position.pitch,
                vehicle.hitboxRoll(entity),
            ) ?: continue

        if (closest == null || t < closest.t) {
            closest = ProjectileImpact(t, ray.origin.add(ray.direction.mul(t)))
        }
    }

    return closest
}

internal fun selectProjectileImpact(
    blockHit: Ray.Hit<Block>?,
    entityHit: Ray.Hit<LivingEntity>?,
    vehicleHit: ProjectileImpact? = null,
): ProjectileImpact? =
    listOfNotNull(
        blockHit?.let { ProjectileImpact(it.t, it.point) },
        entityHit?.let { ProjectileImpact(it.t, it.point) },
        vehicleHit,
    ).minByOrNull { it.t }
