package net.aechronis.combat.objects

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
        false,
        emptySet(),
    )

    private val entity: Entity
    private var velocity: Vec = direction.mul(speed)
    var isActive = true

    init {
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

        // accelerate downward so the projectile arcs over time
        velocity = velocity.sub(0.0, gravity, 0.0)
        val nextPos = currentPos.add(velocity)

        val impact = firstProjectileImpact(Ray(currentPos, velocity), instance, ignoredEntities + listOfNotNull(source))
        if (impact != null) {
            detonate(impact.point.asPos())
            return
        }

        // chunk is loaded
        if (!instance.isChunkLoaded(nextPos)) {
            isActive = false
            entity.remove()
            return
        }

        // move the entity
        entity.teleport(nextPos.withDirection(velocity))
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
