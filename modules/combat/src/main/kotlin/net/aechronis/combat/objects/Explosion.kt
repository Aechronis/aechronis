package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.events.ExplosionBlockChange
import net.aechronis.combat.events.ExplosionBlockChangeType
import net.aechronis.combat.events.ExplosionBlockDamageEvent
import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.withCombatAttribution
import net.aechronis.combat.utils.withCombatDamageImmunityBypass
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
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
import net.minestom.server.registry.RegistryKey
import kotlin.random.Random

private const val SMOKE_SAMPLE_INTERVAL = 8

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
    private val attacker: Entity? = source,
    /** When set, the blast only hurts these entities and leaves blocks intact. */
    private val validTargets: Set<Entity>? = null,
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
        // Take the snapshot before block destruction starts so damage and terrain changes use
        // one consistent view of the blast.
        val blast = collectBlastBlocks()
        val event =
            ExplosionBlockDamageEvent(
                instance = instance,
                position = pos,
                sourceUuid = source?.uuid,
                sourceName = source?.username,
                changes = if (validTargets == null) blast.changes else emptyList(),
                sourcePlayer = source,
            )
        MinecraftServer.getGlobalEventHandler().call(event)
        run explosion@{
            if (event.isCancelled) return@explosion
            if (damage > 0f) applyDamage(blast.affectedBlocks)

            var smokeSampleIndex = 0

            // Emit smoke at every eighth blast position.
            blast.positions.forEach { p ->
                if (smokeSampleIndex++ % SMOKE_SAMPLE_INTERVAL == 0) {
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

            if (validTargets != null) return@explosion

            // First pass: destroy the blocks affected by the explosion. Protected
            // blocks are excluded from this snapshot and therefore remain intact.
            blast.affectedBlocks.forEach { block ->
                BlockRestoreManager.temporarilyReplace(
                    instance,
                    block,
                    requireNotNull(blast.originalBlocks[block]),
                    Block.AIR,
                )
            }

            // Second pass: place the fire positions selected during the snapshot pass.
            blast.firePositions.forEach { block ->
                BlockRestoreManager.temporarilyReplace(
                    instance,
                    block,
                    requireNotNull(blast.originalBlocks[block]),
                    Block.FIRE,
                )
            }
        }
    }

    private fun collectBlastBlocks(): BlastBlocks {
        val radiusSquared = radius * radius
        val positions = mutableListOf<Pos>()
        val blocks = LinkedHashMap<BlockVec, Block>()

        for (x in -radius..radius) {
            val xSquared = x * x
            for (y in -radius..radius) {
                val ySquared = y * y
                for (z in -radius..radius) {
                    if (xSquared + ySquared + z * z > radiusSquared) continue

                    val p = pos.add(x.toDouble(), y.toDouble(), z.toDouble())
                    val block = p.asBlockVec()
                    if (!instance.isChunkLoaded(block.blockX() shr 4, block.blockZ() shr 4)) continue

                    positions.add(p)
                    blocks.putIfAbsent(block, instance.getBlock(block))
                }
            }
        }

        val affectedBlocks =
            blocks
                .filterValues { block -> !block.air() && !isExplosionProof(block) }
                .keys
                .toCollection(LinkedHashSet())
        val changes =
            affectedBlocks
                .map { block ->
                    ExplosionBlockChange(
                        position = block,
                        oldBlock = requireNotNull(blocks[block]),
                        newBlock = Block.AIR,
                        type = ExplosionBlockChangeType.DAMAGE,
                    )
                }.toMutableList()

        val firePositions = mutableListOf<BlockVec>()
        if (fire > 0) {
            blocks.keys.forEach { block ->
                if (isExplosionProof(requireNotNull(blocks[block]))) return@forEach

                val below = BlockVec(block.blockX(), block.blockY() - 1, block.blockZ())
                val blockBelow = if (below in blocks) Block.AIR else instance.getBlock(below)
                if (Random.nextDouble() < fire && blockBelow != Block.AIR && blockBelow.solid()) {
                    firePositions += block
                    changes +=
                        ExplosionBlockChange(
                            position = block,
                            oldBlock = Block.AIR,
                            newBlock = Block.FIRE,
                            type = ExplosionBlockChangeType.FIRE,
                        )
                }
            }
        }

        return BlastBlocks(positions, blocks, affectedBlocks, changes, firePositions)
    }

    private fun isExplosionProof(block: Block): Boolean =
        Combat.config.noBlockExplodeList.any { protectedBlock -> block.compare(protectedBlock) }

    private fun applyDamage(affectedBlocks: Set<BlockVec>) {
        val type = if (source != null) DamageType.PLAYER_EXPLOSION else DamageType.EXPLOSION

        for (runtime in VehicleRegistry.all()) {
            val entity = runtime.entity
            val vehicle = runtime.vehicle
            if (entity.instance != instance || !canDamage(entity)) continue
            val vehiclePosition = entity.position
            val distance =
                vehicle.hitbox.distanceToPoint(
                    pos,
                    vehiclePosition,
                    vehiclePosition.yaw,
                    vehiclePosition.pitch,
                    vehicle.hitboxRoll(entity),
                )
            val blastDamage = damageAtDistance(damage, radius, distance)
            if (blastDamage > 0f) vehicle.takeDamage(entity, ammoType, blastDamage, source, weapon)
        }

        for (player in instance.players.toList()) {
            if (!canDamage(player)) continue
            val blastDistance = explosionDistance(player, affectedBlocks)
            val blastDamage = damageAtDistance(damage, radius, blastDistance)
            if (blastDamage > 0f) applyDamage(player, type, blastDamage)
        }

        for (entity in instance.entities.toList()) {
            if (entity.entityType != EntityType.MANNEQUIN || entity !is LivingEntity || !canDamage(entity)) continue
            // The crew member behind a stand-in is already damaged as a player above.
            if (VehicleRegistry.standInOwner(entity) != null) continue
            val blastDamage = damageAtDistance(damage, radius, distanceToBoundingBox(entity, pos))
            if (blastDamage > 0f) applyDamage(entity, type, blastDamage)
        }
    }

    private fun canDamage(entity: Entity): Boolean = validTargets == null || entity in validTargets

    private fun applyDamage(
        entity: LivingEntity,
        type: RegistryKey<DamageType>,
        amount: Float,
    ) {
        val damageSource =
            Damage(type, attacker, attacker, pos, amount)
                .withCombatAttribution(CombatDamageKind.EXPLOSION, weapon)
        if (bypassDamageImmunity) {
            damageSource.withCombatDamageImmunityBypass()
            Combat.applyDamage(entity, damageSource)
        } else {
            // An explosion must not be dropped merely because another combat event
            // hit the player during the global immunity window.
            Combat.applyDamageWithoutImmunity(entity, damageSource)
        }
    }

    private fun explosionDistance(
        entity: Entity,
        affectedBlocks: Set<BlockVec>,
    ): Double {
        val hitboxDistance = distanceToBoundingBox(entity, pos)
        if (hitboxDistance <= radius) return hitboxDistance

        val boxStart = entity.boundingBox.relativeStart().add(entity.position)
        val boxEnd = entity.boundingBox.relativeEnd().add(entity.position)
        val feetBlockY = kotlin.math.floor(boxStart.y()).toInt() - 1
        val minBlockDistance =
            affectedBlocks
                .asSequence()
                .filter { block ->
                    block.blockY() == feetBlockY &&
                        block.blockX() in floorBlockRange(boxStart.x(), boxEnd.x()) &&
                        block.blockZ() in floorBlockRange(boxStart.z(), boxEnd.z())
                }.map { distanceToBlock(it, pos) }
                .minOrNull()

        return minBlockDistance ?: hitboxDistance
    }

    private fun floorBlockRange(
        start: Double,
        end: Double,
    ): IntRange {
        val min = kotlin.math.floor(start).toInt()
        val max = kotlin.math.floor(end - 1.0E-6).toInt()
        return min..max
    }

    private fun distanceToBlock(
        block: BlockVec,
        point: Point,
    ): Double {
        val closest =
            Vec(
                point.x().coerceIn(block.x(), block.x() + 1.0),
                point.y().coerceIn(block.y(), block.y() + 1.0),
                point.z().coerceIn(block.z(), block.z() + 1.0),
            )
        return point.distance(closest)
    }

    private data class BlastBlocks(
        val positions: List<Pos>,
        val originalBlocks: Map<BlockVec, Block>,
        val affectedBlocks: Set<BlockVec>,
        val changes: List<ExplosionBlockChange>,
        val firePositions: List<BlockVec>,
    )

    companion object {
        fun bypassingDamageImmunity(
            instance: Instance,
            pos: Pos,
            radius: Int,
            fire: Double,
            damage: Float,
            source: Player?,
            weapon: Component?,
            ammoType: AmmoTypes?,
        ): Explosion = Explosion(instance, pos, radius, fire, damage, source, weapon, ammoType, true)

        /** A blast credited to an AI [attacker] that only hurts [validTargets] and leaves blocks intact. */
        fun againstTargets(
            instance: Instance,
            pos: Pos,
            radius: Int,
            damage: Float,
            attacker: LivingEntity,
            validTargets: Set<Entity>,
            weapon: Component?,
            ammoType: AmmoTypes?,
        ): Explosion = Explosion(instance, pos, radius, 0.0, damage, null, weapon, ammoType, false, attacker, validTargets)
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
