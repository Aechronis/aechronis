package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.withCombatAttribution
import net.aechronis.combat.utils.withCombatDamageImmunityBypass
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

class Explosion private constructor(
    val instance: Instance,
    val pos: Pos,
    val radius: Int,
    val fire: Double,
    val damage: Float = 0f,
    val source: Player? = null,
    val weapon: Component? = null,
    val ammoType: AmmoTypes? = null,
    private val bypassDamageImmunity: Boolean,
) {
    constructor(
        instance: Instance,
        pos: Pos,
        radius: Int,
        fire: Double,
        damage: Float = 0f,
        source: Player? = null,
        weapon: Component? = null,
        ammoType: AmmoTypes? = null,
    ) : this(instance, pos, radius, fire, damage, source, weapon, ammoType, false)

    init {
        if (damage > 0f) applyDamage()

        CompletableFuture.runAsync {
            val radiusSquared = radius * radius
            val positions = mutableListOf<Pos>()

            // Collect all positions and send particles
            for (x in -radius..radius) {
                val xSquared = x * x
                for (y in -radius..radius) {
                    val ySquared = y * y
                    for (z in -radius..radius) {
                        // Early distance check using squared distance (faster than distance())
                        if (xSquared + ySquared + z * z > radiusSquared) continue

                        val p = pos.add(x.toDouble(), y.toDouble(), z.toDouble())
                        positions.add(p)

                        instance.sendGroupedPacket(
                            ParticlePacket(
                                Particle.CAMPFIRE_SIGNAL_SMOKE,
                                p,
                                Pos(1.0, 1.0, 1.0),
                                0.05F,
                                1,
                            ),
                        )

                        instance.sendGroupedPacket(
                            ParticlePacket(
                                Particle.CAMPFIRE_COSY_SMOKE,
                                p,
                                Pos(1.0, 1.0, 1.0),
                                0.1F,
                                1,
                            ),
                        )
                    }
                }
            }

            // First pass: destroy all blocks
            positions.forEach { p ->
                instance.setBlock(p, Block.AIR)
            }

            // Second pass: place fire where appropriate (after destruction is complete)
            if (fire > 0) {
                positions.forEach { p ->
                    val blockBelow = instance.getBlock(p.add(0.0, -1.0, 0.0))
                    if (Random.nextDouble() < fire && blockBelow != Block.AIR && blockBelow.isSolid) {
                        instance.setBlock(p, Block.FIRE)
                    }
                }
            }
        }
    }

    private fun applyDamage() {
        val type = if (source != null) DamageType.PLAYER_EXPLOSION else DamageType.EXPLOSION

        for ((entity, vehicle) in Vehicle.entityVehicle.toList()) {
            if (entity.instance != instance) continue
            val blastDamage = damageAtDistance(damage, radius, entity.position.distance(pos))
            if (blastDamage > 0f) vehicle.takeDamage(entity, ammoType, blastDamage, source, weapon)
        }

        for (player in instance.players.toList()) {
            val blastDamage = damageAtDistance(damage, radius, distanceToBoundingBox(player, pos))
            if (blastDamage > 0f) {
                val damageSource =
                    Damage(type, source, source, pos, blastDamage)
                        .withCombatAttribution(CombatDamageKind.EXPLOSION, weapon)
                if (bypassDamageImmunity) damageSource.withCombatDamageImmunityBypass()
                Combat.applyDamage(player, damageSource)
            }
        }

        for (entity in instance.entities.toList()) {
            if (entity.entityType != EntityType.MANNEQUIN || entity !is LivingEntity) continue
            val blastDamage = damageAtDistance(damage, radius, distanceToBoundingBox(entity, pos))
            if (blastDamage > 0f) {
                val damageSource =
                    Damage(type, source, source, pos, blastDamage)
                        .withCombatAttribution(CombatDamageKind.EXPLOSION, weapon)
                if (bypassDamageImmunity) damageSource.withCombatDamageImmunityBypass()
                Combat.applyDamage(entity, damageSource)
            }
        }
    }

    companion object {
        internal fun bypassingDamageImmunity(
            instance: Instance,
            pos: Pos,
            radius: Int,
            fire: Double,
            damage: Float,
            source: Player?,
            weapon: Component?,
            ammoType: AmmoTypes?,
        ): Explosion = Explosion(instance, pos, radius, fire, damage, source, weapon, ammoType, true)
    }
}

internal fun distanceToBoundingBox(
    entity: Entity,
    point: Point,
): Double {
    val boxStart = entity.boundingBox.relativeStart().add(entity.position)
    val boxEnd = entity.boundingBox.relativeEnd().add(entity.position)
    val closest =
        Vec(
            point.x().coerceIn(boxStart.x(), boxEnd.x()),
            point.y().coerceIn(boxStart.y(), boxEnd.y()),
            point.z().coerceIn(boxStart.z(), boxEnd.z()),
        )
    return point.distance(closest)
}

internal fun damageAtDistance(
    maxDamage: Float,
    radius: Int,
    distance: Double,
): Float {
    if (maxDamage <= 0f || distance < 0.0 || distance > radius) return 0f
    if (radius == 0) return maxDamage

    val minimumDamage = minOf(1f, maxDamage)
    val falloffDamage = maxDamage * (1f - (distance / radius).toFloat())
    return falloffDamage.coerceIn(minimumDamage, maxDamage)
}
