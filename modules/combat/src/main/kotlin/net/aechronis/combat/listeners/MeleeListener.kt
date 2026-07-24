package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Melee
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.withCombatAttribution
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.network.packet.server.play.EntityAnimationPacket
import net.minestom.server.particle.Particle
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object MeleeListener {
    private fun onEntityAttack(event: EntityAttackEvent) {
        val attacker = event.entity as? Player ?: return
        val target = event.target as? LivingEntity ?: return
        if (attacker.isDead || attacker.gameMode == GameMode.SPECTATOR) return
        if (target.isDead || (target as? Player)?.gameMode == GameMode.SPECTATOR) return

        val currentTime = System.currentTimeMillis()
        val melee = Item.getFromItemStack(attacker.itemInMainHand) as? Melee ?: return
        val cooldownMs = currentTime - (Combat.meleeLastAttackTimes[attacker] ?: 0L)
        Combat.meleeLastAttackTimes[attacker] = currentTime

        // check invincibility frames
        if (!Combat.canDamage(target, currentTime)) {
            // target is invincible
            playAttackSound(attacker, isStrong = false, isCritical = false, cooldownProgress = 0.0)
            return
        }

        // cooldown progress
        val attackSpeedTicks = (1.0 / melee.attackSpeed) * 20.0
        val cooldownTicks = cooldownMs / 50.0
        val cooldownProgress = ((cooldownTicks + 0.5) / attackSpeedTicks).coerceIn(0.0, 1.0)

        // damage with cooldown modifier
        val cooldownModifier = 0.2 + (cooldownProgress * cooldownProgress) * 0.8
        var damage = melee.damage * cooldownModifier

        val isStrongAttack = cooldownProgress > 0.9

        val isCritical =
            isStrongAttack &&
                isPlayerFalling(attacker) &&
                !attacker.isOnGround &&
                attacker.vehicle == null &&
                !attacker.isSprinting

        if (isCritical) {
            damage *= 1.5
        }

        // apply damage and set invincibility frames
        val damageSource =
            Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, damage.toFloat())
                .withCombatAttribution(CombatDamageKind.MELEE, melee.itemName)
        val didDamage = Combat.applyDamage(target, damageSource, currentTime)
        if (!didDamage) return
        if (isCritical) {
            playCriticalParticles(target)
        }

        // check for sprint hit
        val isSprintAttack = isStrongAttack && attacker.isSprinting

        // apply knockback
        applyKnockback(attacker, target, melee.knockback, isSprintAttack)

        // stop sprinting after sprint attack
        if (isSprintAttack) {
            attacker.isSprinting = false
            // knockback sound
            attacker.instance?.playSound(
                Sound.sound(Key.key("entity.player.attack.knockback"), Sound.Source.PLAYER, 1.0f, 1.0f),
                attacker.position,
            )
        }

        // attack sounds
        playAttackSound(attacker, isStrongAttack, isCritical, cooldownProgress)

        // check for sweep hit
        val isSweeping =
            isStrongAttack &&
                !isCritical &&
                !isSprintAttack &&
                attacker.isOnGround &&
                melee.sweepable &&
                isPlayerStationary(attacker)

        if (isSweeping) {
            performSweepingAttack(attacker, target)
        }
    }

    private fun applyKnockback(
        attacker: Player,
        target: LivingEntity,
        baseKnockback: Double,
        isSprintAttack: Boolean,
    ) {
        val yawRad = Math.toRadians(attacker.position.yaw.toDouble())
        var dx = -sin(yawRad)
        var dz = cos(yawRad)
        if (dx * dx + dz * dz < 1.0e-6) {
            dx = 0.0
            dz = 1.0
        }

        val resistance =
            target.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.value?.coerceIn(0.0, 1.0) ?: 0.0
        val horizontalKb = (baseKnockback * 20.0 + if (isSprintAttack) 10.0 else 0.0) * (1.0 - resistance)

        val currentVel = target.velocity

        val newVelX = currentVel.x / 2.0 + dx * horizontalKb
        val newVelZ = currentVel.z / 2.0 + dz * horizontalKb
        val newVelY =
            if (target.isOnGround) {
                min(8.0, currentVel.y / 2.0 + 8.0 * (1.0 - resistance))
            } else {
                currentVel.y
            }

        target.velocity = Vec(newVelX, newVelY, newVelZ)
    }

    private fun performSweepingAttack(
        attacker: Player,
        primaryTarget: LivingEntity,
    ) {
        // sweeping particles and sound
        playSweepingParticles(attacker)
        attacker.instance?.playSound(
            Sound.sound(Key.key("entity.player.attack.sweep"), Sound.Source.PLAYER, 1.0f, 1.0f),
            attacker.position,
        )

        // get entities within range of the primary target
        val targetPos = primaryTarget.position
        val currentTime = System.currentTimeMillis()
        val nearbyEntities =
            attacker.instance
                ?.entities
                ?.filter { entity ->
                    if (entity == attacker || entity == primaryTarget) return@filter false
                    if (entity !is LivingEntity) return@filter false
                    if (entity.position.distanceSquared(targetPos) > 4.0) return@filter false
                    entity.intersectBox(targetPos, primaryTarget.boundingBox.grow(1.0, 0.25, 1.0))
                }

        nearbyEntities?.forEach { entity ->
            val livingEntity = entity as LivingEntity

            // invincibility frames
            if (!Combat.canDamage(livingEntity, currentTime)) {
                return@forEach // Skip this entity, still invincible
            }

            // apply sweeping damage and set iframes
            val damageSource =
                Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, 1F)
                    .withCombatAttribution(CombatDamageKind.MELEE, meleeWeaponName(attacker))
            if (!Combat.applyDamage(livingEntity, damageSource, currentTime)) {
                return@forEach
            }

            // apply sweeping knockback
            applySweepingKnockback(attacker, livingEntity)
        }
    }

    private fun meleeWeaponName(attacker: Player) = (Item.getFromItemStack(attacker.itemInMainHand) as? Melee)?.itemName

    private fun applySweepingKnockback(
        attacker: Player,
        target: LivingEntity,
    ) {
        val yawRad = Math.toRadians(attacker.position.yaw.toDouble())
        val dx = -sin(yawRad)
        val dz = cos(yawRad)

        val currentVel = target.velocity
        val resistance =
            target.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.value?.coerceIn(0.0, 1.0) ?: 0.0
        val knockback = 4.0 * (1.0 - resistance)
        val newVelX = currentVel.x / 2.0 + dx * knockback
        val newVelZ = currentVel.z / 2.0 + dz * knockback

        val newVelY =
            if (target.isOnGround) {
                min(8.0, currentVel.y / 2.0 + 8.0 * (1.0 - resistance))
            } else {
                currentVel.y
            }

        target.velocity = Vec(newVelX, newVelY, newVelZ)
    }

    private fun isPlayerFalling(player: Player): Boolean {
        val positions = Combat.playerPreviousPositions[player]
        if (positions == null || positions.size < 2) return player.velocity.y < 0.0

        // get distance moved in last tick
        val lastY = positions.last().y
        val prevY = positions.elementAtOrNull(positions.size - 2)?.y ?: return true

        return prevY > lastY
    }

    private fun isPlayerStationary(player: Player): Boolean {
        val positions = Combat.playerPreviousPositions[player]
        if (positions == null || positions.size < 2) return true

        // get distance moved in last tick
        val lastPos = positions.last()
        val prevPos = positions.elementAtOrNull(positions.size - 2) ?: return true
        val lastMoveDistance = prevPos.distance(lastPos)

        return lastMoveDistance < 0.2
    }

    private fun playAttackSound(
        attacker: Player,
        isStrong: Boolean,
        isCritical: Boolean,
        cooldownProgress: Double,
    ) {
        val sound =
            when {
                isCritical -> Sound.sound(Key.key("entity.player.attack.crit"), Sound.Source.PLAYER, 1.0f, 1.0f)
                isStrong -> Sound.sound(Key.key("entity.player.attack.strong"), Sound.Source.PLAYER, 1.0f, 1.0f)
                cooldownProgress < 0.2 -> Sound.sound(Key.key("entity.player.attack.weak"), Sound.Source.PLAYER, 1.0f, 1.0f)
                else -> Sound.sound(Key.key("entity.player.attack.nodamage"), Sound.Source.PLAYER, 1.0f, 1.0f)
            }
        attacker.instance?.playSound(sound, attacker.position)
    }

    private fun playCriticalParticles(target: LivingEntity) {
        // critical hit animation packet
        target.sendPacketToViewersAndSelf(EntityAnimationPacket(target.entityId, EntityAnimationPacket.Animation.CRITICAL_EFFECT))
    }

    private fun playSweepingParticles(attacker: Player) {
        // spawn sweep attack particles in front of the player
        val yawRad = Math.toRadians(attacker.position.yaw.toDouble())
        val particleX = attacker.position.x - sin(yawRad)
        val particleY = attacker.position.y + 0.5
        val particleZ = attacker.position.z + cos(yawRad)

        attacker.instance?.sendGroupedPacket(
            net.minestom.server.network.packet.server.play.ParticlePacket(
                Particle.SWEEP_ATTACK,
                particleX,
                particleY,
                particleZ,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                1,
            ),
        )
    }

    private fun onHandAnimation(event: PlayerHandAnimationEvent) {
        val player = event.player
        if (Item.getFromItemStack(player.itemInMainHand) !is Melee) return
        Combat.meleeLastAttackTimes[player] = System.currentTimeMillis()
    }

    fun init() {
        Combat.eventNode.addListener(EntityAttackEvent::class.java, MeleeListener::onEntityAttack)
        Combat.eventNode.addListener(PlayerHandAnimationEvent::class.java, MeleeListener::onHandAnimation)
    }
}
