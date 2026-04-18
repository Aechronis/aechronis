package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Melee
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.network.packet.server.play.EntityAnimationPacket
import net.minestom.server.particle.Particle
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object MeleeListener {
    private fun onEntityAttack(event: EntityAttackEvent) {
        val attacker = event.entity as? Player ?: return
        val target = event.target as? LivingEntity ?: return

        // reset cooldown on any attack to prevent accumulation
        val cooldownMs = Combat.playerCooldowns[attacker] ?: 0L
        Combat.playerCooldowns[attacker] = 0

        val melee = Item.getFromItemStack(attacker.itemInMainHand) as? Melee ?: return

        // check invincibility frames
        val currentTime = System.currentTimeMillis()
        val lastDamageTime = Combat.entityLastDamageTime[target] ?: 0L
        if (currentTime - lastDamageTime < 500) {
            // target is invincible
            playAttackSound(attacker, false, false, 0.0)
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
            playCriticalParticles(target)
        }

        // set killer for death messages
        if (target is Player) {
            Combat.playerKillers[target] = attacker
        }

        // apply damage and set invincibility frames
        target.damage(Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, damage.toFloat()))
        Combat.entityLastDamageTime[target] = currentTime

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
        var dx = attacker.position.x - target.position.x
        var dz = attacker.position.z - target.position.z

        val dist = sqrt(dx * dx + dz * dz)
        dx /= dist
        dz /= dist

        val horizontalKb = baseKnockback * 20

        var currentVel = target.velocity

        // apply base knockback
        var newVelX = currentVel.x / 2.0 - dx * horizontalKb
        var newVelZ = currentVel.z / 2.0 - dz * horizontalKb
        var newVelY =
            if (target.isOnGround) {
                min(8.0, currentVel.y / 2.0 + 8.0)
            } else {
                currentVel.y
            }

        target.velocity = Vec(newVelX, newVelY, newVelZ)

        // apply additional sprint knockback
        if (isSprintAttack) {
            val yawRad = Math.toRadians(attacker.position.yaw.toDouble())
            val yawDx = sin(yawRad)
            val yawDz = -cos(yawRad)

            currentVel = target.velocity
            newVelX = currentVel.x / 2.0 - yawDx * 10
            newVelZ = currentVel.z / 2.0 - yawDz * 10
            newVelY = if (target.isOnGround) {
                    min(8.0, currentVel.y / 2.0 + 10.0)
                } else {
                    currentVel.y
                }

            target.velocity = Vec(newVelX, newVelY, newVelZ)
        }
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
        val targetBox = primaryTarget.boundingBox
        val currentTime = System.currentTimeMillis()
        val nearbyEntities =
            attacker.instance
                ?.entities
                ?.filter { entity ->
                    if (entity == attacker || entity == primaryTarget) return@filter false
                    if (entity !is LivingEntity) return@filter false
                    // check distance from target
                    if (entity.position.distance(targetPos) > 2.0) return@filter false
                    // check attacker distance
                    if (attacker.position.distanceSquared(entity.position) >= 9.0) return@filter false
                    // check bounding box intersection
                    val dx = kotlin.math.abs(entity.position.x - targetPos.x)
                    val dy = kotlin.math.abs(entity.position.y - targetPos.y)
                    val dz = kotlin.math.abs(entity.position.z - targetPos.z)
                    dx <= targetBox.width() / 2 + 1.0 &&
                        dy <= targetBox.height() + 0.25 &&
                        dz <= targetBox.depth() / 2 + 1.0
                }

        nearbyEntities?.forEach { entity ->
            val livingEntity = entity as LivingEntity

            // invincibility frames
            val lastDamageTime = Combat.entityLastDamageTime[livingEntity] ?: 0L
            if (currentTime - lastDamageTime < 500) {
                return@forEach // Skip this entity, still invincible
            }

            // track killer for death messages
            if (livingEntity is Player) {
                Combat.playerKillers[livingEntity] = attacker
            }

            // apply sweeping damage and set iframes
            livingEntity.damage(Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, 1F))
            Combat.entityLastDamageTime[livingEntity] = currentTime

            // apply sweeping knockback
            applySweepingKnockback(attacker, livingEntity)
        }
    }

    private fun applySweepingKnockback(
        attacker: Player,
        target: LivingEntity,
    ) {
        val yawRad = Math.toRadians(attacker.position.yaw.toDouble())
        val dx = -sin(yawRad)
        val dz = cos(yawRad)

        val currentVel = target.velocity
        val newVelX = currentVel.x / 2.0 + dx * 4
        val newVelZ = currentVel.z / 2.0 + dz * 4

        val newVelY =
            if (target.isOnGround) {
                min(8.0, currentVel.y / 2.0 + 8.0)
            } else {
                currentVel.y
            }

        target.velocity = Vec(newVelX, newVelY, newVelZ)
    }

    private fun isPlayerFalling(player: Player): Boolean {
        val positions = Combat.playerPreviousPositions[player]
        if (positions == null || positions.size < 2) return true

        // get distance moved in last tick
        val lastY = positions.last().y
        val prevY = positions.elementAtOrNull(positions.size - 2)?.y ?: return true

        return prevY >= lastY
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
        // reset cooldown on swing
        Combat.playerCooldowns[player] = 0
    }

    fun init() {
        Combat.eventNode.addListener(EntityAttackEvent::class.java, MeleeListener::onEntityAttack)
        Combat.eventNode.addListener(PlayerHandAnimationEvent::class.java, MeleeListener::onHandAnimation)
    }
}
