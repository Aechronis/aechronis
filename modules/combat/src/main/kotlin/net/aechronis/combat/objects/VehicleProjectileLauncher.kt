package net.aechronis.combat.objects

import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.rotatePoint
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.particle.Particle

/** Shell launch behavior shared by tank guns and field cannons. */
internal class VehicleProjectileLauncher(
    private val barrelTipOffset: Vec,
    private val model: String,
    private val name: Component,
    private val speed: Double,
    private val explosionRadius: Int,
    private val explosionFire: Double,
    private val explosionDamage: Float,
    private val ammoType: AmmoTypes,
    private val trailParticle: Particle?,
    private val trailSpacing: Double,
    private val trailMaxParticles: Int,
    private val maxRange: Double,
) {
    /** Returns false if the body is no longer in an instance; callers spend ammo only after a launch. */
    fun fire(
        player: Player,
        body: Entity,
        barrelPos: Pos,
        yaw: Float,
        pitch: Float,
    ): Boolean {
        val instance = body.instance ?: return false
        val tip = rotatePoint(barrelTipOffset, yaw, pitch, 0f)
        val muzzle = barrelPos.add(tip.x, tip.y, tip.z)
        val direction = muzzle.withView(yaw, pitch).direction()
        val ignoredEntities =
            buildSet<Entity> {
                add(body)
                add(player)
                addAll(VehicleRegistry.ridesOf(body).map { it.player })
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
                radius = explosionRadius,
                fire = explosionFire,
                damage = explosionDamage,
                source = player,
                weapon = name,
                ammoType = ammoType,
            )
        } else {
            Projectile.bypassingDamageImmunity(
                instance = instance,
                pos = muzzle,
                model = model,
                direction = direction,
                speed = speed,
                explosionRadius = explosionRadius,
                explosionFire = explosionFire,
                explosionDamage = explosionDamage,
                source = player,
                weapon = name,
                ignoredEntities = ignoredEntities,
                ammoType = ammoType,
                trailParticle = trailParticle,
                trailSpacing = trailSpacing,
                trailMaxParticles = trailMaxParticles,
                maxRange = maxRange,
            )
        }
        return true
    }
}
