package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Melee
import net.aechronis.combat.objects.VehicleRegistry
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.MeleeHit
import net.aechronis.combat.utils.isPartialHit
import net.aechronis.combat.utils.withCombatAttribution
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.entity.metadata.other.ArmorStandMeta
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.event.player.PlayerTickEvent
import net.minestom.server.network.packet.server.play.EntityAnimationPacket
import net.minestom.server.particle.Particle
import net.minestom.server.potion.PotionEffect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object MeleeListener {
    private const val TICKS_PER_SECOND = 20.0
    private const val SPRINT_KNOCKBACK = 0.5
    private const val SWEEP_KNOCKBACK = 0.4
    private const val SWEEP_RANGE = 3.0

    private fun onEntityAttack(event: EntityAttackEvent) {
        val attacker = event.entity as? Player ?: return
        val target = event.target as? LivingEntity ?: return
        if (attacker.isDead || attacker.gameMode == GameMode.SPECTATOR) return
        if (target.isDead || (target as? Player)?.gameMode == GameMode.SPECTATOR) return

        val currentTime = System.currentTimeMillis()
        val melee = Item.getFromItemStack(attacker.itemInMainHand) as? Melee ?: return
        val cooldownMs = currentTime - (Combat.playerStates[attacker]?.meleeLastAttackTime ?: 0L)
        Combat.playerStates.getOrCreate(attacker).meleeLastAttackTime = currentTime

        // cooldown progress
        val attackSpeedTicks = (1.0 / melee.attackSpeed) * 20.0
        val cooldownTicks = cooldownMs / 50.0
        val cooldownProgress = ((cooldownTicks + 0.5) / attackSpeedTicks).coerceIn(0.0, 1.0)

        // damage with cooldown modifier
        val cooldownModifier = 0.2 + (cooldownProgress * cooldownProgress) * 0.8
        var damage = melee.damage * cooldownModifier

        val isStrongAttack = cooldownProgress > 0.9

        // check for sprint hit
        val isSprintAttack = isStrongAttack && attacker.isSprinting
        if (isSprintAttack) playAttackSound(attacker, "entity.player.attack.knockback")

        val isCritical = isStrongAttack && canCriticalAttack(attacker)

        if (isCritical) {
            damage *= 1.5
        }

        // check for sweep hit
        val isSweeping =
            isStrongAttack &&
                !isCritical &&
                !isSprintAttack &&
                attacker.isOnGround &&
                melee.sweepable &&
                isSlowEnoughToSweep(attacker)

        // apply damage and set invincibility frames
        val damageSource =
            Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, damage.toFloat())
                .withCombatAttribution(CombatDamageKind.MELEE, melee.itemName)
        val hit = Combat.applyMeleeDamage(target, damageSource, currentTime)
        if (hit == MeleeHit.NONE) {
            // a stand-in always reports no damage, even when its owner was hit
            if (VehicleRegistry.standInOwner(target) == null) playAttackSound(attacker, "entity.player.attack.nodamage")
            return
        }

        // apply knockback
        applyKnockback(attacker, target, hit, melee.knockback, if (isSprintAttack) SPRINT_KNOCKBACK else 0.0)

        // stop sprinting after sprint attack
        if (isSprintAttack) {
            attacker.isSprinting = false
        }

        if (isSweeping) {
            performSweepingAttack(attacker, target, cooldownProgress, currentTime)
        }

        // attack sounds
        if (isCritical) {
            playAttackSound(attacker, "entity.player.attack.crit")
            playCriticalParticles(target)
        } else if (!isSweeping) {
            playAttackSound(attacker, if (isStrongAttack) "entity.player.attack.strong" else "entity.player.attack.weak")
        }
    }

    private fun canCriticalAttack(attacker: Player): Boolean =
        (Combat.playerStates[attacker]?.fallDistance ?: 0.0) > 0.0 &&
            !attacker.isOnGround &&
            !FallDistanceListener.isOnClimbable(attacker) &&
            !FallDistanceListener.isInWater(attacker) &&
            !attacker.hasEffect(PotionEffect.BLINDNESS) &&
            attacker.vehicle == null &&
            !attacker.isSprinting

    /** Combines vanilla's two impulses into one velocity update: away from the attacker, then along their facing. */
    private fun applyKnockback(
        attacker: Player,
        target: LivingEntity,
        hit: MeleeHit,
        basePower: Double,
        facingPower: Double,
    ) {
        var velocity = target.velocity

        // partial hits skip the default knockback, like vanilla
        if (hit == MeleeHit.FULL) {
            val xd = attacker.position.x - target.position.x
            val zd = attacker.position.z - target.position.z
            velocity = knockedBack(target, velocity, basePower, xd, zd)
        }

        if (facingPower > 0.0) {
            val yawRad = Math.toRadians(attacker.position.yaw.toDouble())
            velocity = knockedBack(target, velocity, facingPower, sin(yawRad), -cos(yawRad))
        }

        if (velocity != target.velocity) target.velocity = velocity
    }

    private fun knockedBack(
        target: LivingEntity,
        velocity: Vec,
        power: Double,
        xd: Double,
        zd: Double,
    ): Vec {
        val resistance =
            target.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.value?.coerceIn(0.0, 1.0) ?: 0.0
        val impulse = power * (1.0 - resistance) * TICKS_PER_SECOND
        if (impulse <= 0.0) return velocity

        var dirX = xd
        var dirZ = zd
        while (dirX * dirX + dirZ * dirZ < 1.0e-5) {
            dirX = (Random.nextDouble() - Random.nextDouble()) * 0.01
            dirZ = (Random.nextDouble() - Random.nextDouble()) * 0.01
        }
        val length = sqrt(dirX * dirX + dirZ * dirZ)

        return Vec(
            velocity.x / 2.0 - dirX / length * impulse,
            if (target.isOnGround) min(0.4 * TICKS_PER_SECOND, velocity.y / 2.0 + impulse) else velocity.y,
            velocity.z / 2.0 - dirZ / length * impulse,
        )
    }

    private fun performSweepingAttack(
        attacker: Player,
        primaryTarget: LivingEntity,
        cooldownProgress: Double,
        currentTime: Long,
    ) {
        // sweeping particles and sound
        playSweepingParticles(attacker)
        playAttackSound(attacker, "entity.player.attack.sweep")

        val attackerPos = attacker.position
        val targetPos = primaryTarget.position
        val nearbyEntities =
            attacker.instance
                ?.getNearbyEntities(attackerPos, SWEEP_RANGE)
                ?.filter { entity ->
                    if (entity == attacker || entity == primaryTarget) return@filter false
                    if (entity !is LivingEntity) return@filter false
                    if ((entity.entityMeta as? ArmorStandMeta)?.isMarker == true) return@filter false
                    if (attackerPos.distanceSquared(entity.position) >= SWEEP_RANGE * SWEEP_RANGE) return@filter false
                    // vanilla sweep zone: the target's hitbox inflated by 1 block horizontally and 0.25 vertically
                    val reachX = (primaryTarget.boundingBox.width() + entity.boundingBox.width()) / 2.0 + 1.0
                    val reachZ = (primaryTarget.boundingBox.depth() + entity.boundingBox.depth()) / 2.0 + 1.0
                    abs(entity.position.x - targetPos.x) < reachX &&
                        abs(entity.position.z - targetPos.z) < reachZ &&
                        entity.position.y < targetPos.y + primaryTarget.boundingBox.height() + 0.25 &&
                        entity.position.y + entity.boundingBox.height() > targetPos.y - 0.25
                }

        nearbyEntities?.forEach { entity ->
            val livingEntity = entity as LivingEntity

            // apply sweeping damage and set iframes
            val damageSource =
                Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, cooldownProgress.toFloat())
                    .withCombatAttribution(CombatDamageKind.MELEE, meleeWeaponName(attacker))
            val hit = Combat.applyMeleeDamage(livingEntity, damageSource, currentTime)
            if (hit == MeleeHit.NONE) return@forEach

            // apply sweeping knockback
            applyKnockback(attacker, livingEntity, hit, SWEEP_KNOCKBACK, SWEEP_KNOCKBACK)
        }
    }

    private fun meleeWeaponName(attacker: Player) = (Item.getFromItemStack(attacker.itemInMainHand) as? Melee)?.itemName

    private fun isSlowEnoughToSweep(player: Player): Boolean {
        val positions = Combat.playerStates[player]?.previousPositions
        if (positions == null || positions.size < 2) return true

        // get horizontal distance moved in last tick
        val lastPos = positions.last()
        val prevPos = positions.elementAtOrNull(positions.size - 2) ?: return true
        val dx = lastPos.x - prevPos.x
        val dz = lastPos.z - prevPos.z
        val maxSpeed = player.getAttributeValue(Attribute.MOVEMENT_SPEED) * 2.5

        return dx * dx + dz * dz < maxSpeed * maxSpeed
    }

    private fun playAttackSound(
        attacker: Player,
        sound: String,
    ) {
        attacker.instance?.playSound(Sound.sound(Key.key(sound), Sound.Source.PLAYER, 1.0f, 1.0f), attacker.position)
    }

    private fun playCriticalParticles(target: LivingEntity) {
        // critical hit animation packet
        target.sendPacketToViewersAndSelf(EntityAnimationPacket(target.entityId, EntityAnimationPacket.Animation.CRITICAL_EFFECT))
    }

    private fun playSweepingParticles(attacker: Player) {
        val yawRad = Math.toRadians(attacker.position.yaw.toDouble())
        val dx = -sin(yawRad)
        val dz = cos(yawRad)
        val particleX = attacker.position.x + dx
        val particleY = attacker.position.y + attacker.boundingBox.height() * 0.5
        val particleZ = attacker.position.z + dz

        attacker.instance?.sendGroupedPacket(
            net.minestom.server.network.packet.server.play.ParticlePacket(
                Particle.SWEEP_ATTACK,
                particleX,
                particleY,
                particleZ,
                dx.toFloat(),
                0.0f,
                dz.toFloat(),
                0.0f,
                0,
            ),
        )
    }

    private fun onHandAnimation(event: PlayerHandAnimationEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val player = event.player
        if (Item.getFromItemStack(player.itemInMainHand) !is Melee) return
        Combat.playerStates.getOrCreate(player).meleeLastAttackTime = System.currentTimeMillis()
    }

    private fun onPlayerTick(event: PlayerTickEvent) {
        val player = event.player
        val state = Combat.playerStates.getOrCreate(player)
        val heldItem = player.itemInMainHand
        val heldType = Item.getFromItemStack(heldItem)?.name ?: heldItem.material().name()
        if (heldType == state.lastMainHandType) return

        state.lastMainHandType = heldType
        state.meleeLastAttackTime = System.currentTimeMillis()
    }

    private fun onEntityDamage(event: EntityDamageEvent) {
        if (!event.damage.isPartialHit()) return

        // vanilla shows no hurt effects when a hit only tops up the previous one
        event.setAnimation(false)
        event.sound = null
    }

    fun init() {
        Combat.eventNode.addListener(EntityAttackEvent::class.java, MeleeListener::onEntityAttack)
        Combat.eventNode.addListener(EntityDamageEvent::class.java, MeleeListener::onEntityDamage)
        Combat.eventNode.addListener(PlayerHandAnimationEvent::class.java, MeleeListener::onHandAnimation)
        Combat.eventNode.addListener(PlayerTickEvent::class.java, MeleeListener::onPlayerTick)
    }
}
