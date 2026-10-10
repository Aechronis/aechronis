package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.Particles
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.withCombatAttribution
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.instance.Instance

/** Resolves all pellets in one shot, counting at most one hit against each vehicle. */
internal class GunShot(
    private val gun: Gun,
    private val shooter: LivingEntity,
) {
    private val damagedVehicles = HashSet<Entity>()

    class Hit(
        val trailEndPoint: Pos,
        val entity: LivingEntity? = null,
        private val vehicle: Boolean = false,
    ) {
        val hitTarget: Boolean get() = entity != null || vehicle
    }

    /** The caller supplies eligible entity hits, including lag compensation when appropriate. */
    fun resolve(
        instance: Instance,
        origin: Pos,
        breakLeaves: Boolean = false,
        validVehicles: Set<Entity>? = null,
        ignoredEntities: Set<Entity> = emptySet(),
        findEntity: (Ray) -> Ray.Hit<LivingEntity>?,
    ): Hit {
        val ray = Ray(origin, origin.direction().mul(gun.maxRange))
        val blockHit = ray.firstBlock(instance)
        val entityHit = findEntity(ray)
        val vehicleHit = firstVehicleHit(instance, ray, validVehicles, ignoredEntities)

        val blockDistance = blockHit?.t ?: Double.POSITIVE_INFINITY
        val entityDistance = entityHit?.t ?: Double.POSITIVE_INFINITY
        val vehicleDistance = vehicleHit?.t ?: Double.POSITIVE_INFINITY

        // Strict comparisons preserve block-over-entity and entity-over-vehicle tie priority.
        if (vehicleHit != null && vehicleDistance < blockDistance && vehicleDistance < entityDistance) {
            val (entity, vehicle) = vehicleHit.obj
            val hitPoint = vehicleHit.point.asPos()
            Particles.dustParticle(instance, hitPoint)
            if (damagedVehicles.add(entity)) {
                vehicle.takeDamage(entity, gun.ammo.ammoType, gun.damageAt(vehicleDistance), shooter as? Player, gun.itemName)
            }
            return Hit(hitPoint, vehicle = true)
        }
        if (entityHit != null && entityDistance < blockDistance) {
            val hitPoint = entityHit.point.asPos()
            Particles.bloodParticle(instance, hitPoint)
            val damage =
                Damage
                    .fromProjectile(shooter, null, gun.damageAt(origin.distance(entityHit.point)))
                    .withCombatAttribution(CombatDamageKind.PROJECTILE, gun.itemName)
            Combat.applyDamageWithoutImmunity(entityHit.obj, damage)
            return Hit(hitPoint, entity = entityHit.obj)
        }
        if (blockHit != null) {
            val hitPoint = blockHit.point.asPos()
            Particles.dustParticle(instance, hitPoint)
            if (breakLeaves) BlockRestoreManager.temporarilyBreakLeaf(instance, blockHit.point.asBlockVec(), blockHit.obj)
            return Hit(hitPoint)
        }
        return Hit(origin.add(ray.direction.mul(ray.distance)))
    }

    private fun firstVehicleHit(
        instance: Instance,
        ray: Ray,
        validVehicles: Set<Entity>?,
        ignoredEntities: Set<Entity>,
    ): Ray.Hit<Pair<Entity, Vehicle>>? {
        if (ray.distance <= 0.0 || ray.direction.lengthSquared() == 0.0) return null
        var closest: Ray.Hit<Pair<Entity, Vehicle>>? = null
        for (runtime in VehicleRegistry.all()) {
            val entity = runtime.entity
            val vehicle = runtime.vehicle
            if (entity.instance != instance || entity in ignoredEntities) continue
            if (validVehicles != null && entity !in validVehicles) continue
            val position = entity.position
            val distance =
                vehicle.hitbox.firstIntersection(
                    ray.origin,
                    ray.vector,
                    position,
                    position.yaw,
                    position.pitch,
                    vehicle.hitboxRoll(entity),
                ) ?: continue
            if (closest == null || distance < closest.t) {
                closest = Ray.Hit(distance, ray.origin.add(ray.direction.mul(distance)), entity to vehicle)
            }
        }
        return closest
    }
}
