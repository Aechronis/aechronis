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

        // accelerate downward so the projectile arcs over time
        velocity = velocity.sub(0.0, gravity, 0.0)

        val currentPos = entity.position
        val nextPos = currentPos.add(velocity)

        val impact = firstProjectileImpact(Ray(currentPos, velocity), instance, ignoredEntities + listOfNotNull(source))
        if (impact != null) {
            if (bypassDamageImmunity) {
                Explosion.bypassingDamageImmunity(
                    instance = instance,
                    pos = impact.point.asPos(),
                    radius = explosionRadius,
                    fire = explosionFire,
                    damage = explosionDamage,
                    source = source,
                    weapon = weapon,
                )
            } else {
                Explosion(
                    instance = instance,
                    pos = impact.point.asPos(),
                    radius = explosionRadius,
                    fire = explosionFire,
                    damage = explosionDamage,
                    source = source,
                    weapon = weapon,
                )
            }
            isActive = false
            entity.remove()
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
    return selectProjectileImpact(blockHit, entityHit)
}

internal fun selectProjectileImpact(
    blockHit: Ray.Hit<Block>?,
    entityHit: Ray.Hit<LivingEntity>?,
): ProjectileImpact? =
    if (entityHit != null && (blockHit == null || entityHit.t < blockHit.t)) {
        ProjectileImpact(entityHit.t, entityHit.point)
    } else {
        blockHit?.let { ProjectileImpact(it.t, it.point) }
    }
