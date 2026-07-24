package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.minestom.server.coordinate.Pos
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

class Explosion(
    val instance: Instance,
    val pos: Pos,
    val radius: Int,
    val fire: Double,
    val damage: Float = 0f,
    val source: Player? = null,
) {
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
            if (blastDamage > 0f) vehicle.takeDamage(entity, blastDamage, source)
        }

        for (player in instance.players.toList()) {
            val blastDamage = damageAtDistance(damage, radius, player.position.distance(pos))
            if (blastDamage > 0f && Combat.canDamage(player)) {
                val damaged = player.damage(Damage(type, source, source, pos, blastDamage))
                if (damaged) {
                    Combat.recordDamage(player)
                    if (source != null && player != source) Combat.recordKiller(player, source)
                }
            }
        }

        for (entity in instance.entities.toList()) {
            if (entity.entityType != EntityType.MANNEQUIN || entity !is LivingEntity) continue
            val blastDamage = damageAtDistance(damage, radius, entity.position.distance(pos))
            if (blastDamage > 0f &&
                Combat.canDamage(entity) &&
                entity.damage(Damage(type, source, source, pos, blastDamage))
            ) {
                Combat.recordDamage(entity)
            }
        }
    }
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
