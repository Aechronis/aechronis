package net.aechronis.nodes.colonization

import net.aechronis.combat.objects.Vehicle
import net.aechronis.nodes.Message
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Attack
import net.minestom.server.entity.Player

private const val DEFENDER_DECISION_MILLIS = 100L

internal enum class FlagBreakingOutcome {
    CONTINUE_MINING,
    ATTACK_CANCELLED,
}

/** Translates AI observations into aiming, weapon use, and flag mining. */
internal class DefenderCombat(private val sessions: DefenseSessions) {
    fun updateDefenderAim(
        defender: AiDefender,
        decision: DefenderDecision,
        targetPlayer: Player?,
    ) {
        defender.motor.lookTarget = targetPlayer
            ?.takeIf { decision.memory.targetWasVisible }
            ?.let { player ->
                Vehicle.protectedVehicleAimPosition(player)
                    ?: player.position.add(0.0, player.eyeHeight, 0.0)
            } ?: decision.aimPosition?.toPos()
    }

    fun tickFlagBreaking(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
        now: Long,
    ): FlagBreakingOutcome {
        if (
            defender.blockBreaker.kind != DefenderBlockBreakKind.FLAG ||
            defender.blockBreaker.attack !== attack
        ) {
            beginFlagBreaking(session, defender, attack)
        }
        when (
            defender.blockBreaker.tick(
                instance = session.instance,
                nowMillis = now,
                restartWhenBlockChanges = true,
                canContinue = { position, _ -> position == attack.flagBlock && isAttackActive(attack) },
            )
        ) {
            DefenderBlockBreakTickResult.COMPLETED -> {
                val location = attack.flagBlock
                attack.cancel()
                Message.broadcast(
                    "${ChatColor.GOLD}[Colonization] AI defenders broke the flag at " +
                        "(${location.blockX}, ${location.blockY}, ${location.blockZ})",
                )
                return FlagBreakingOutcome.ATTACK_CANCELLED
            }
            DefenderBlockBreakTickResult.INVALID -> {
                if (session.instance.getBlock(attack.flagBlock).isAir) {
                    attack.cancel()
                    return FlagBreakingOutcome.ATTACK_CANCELLED
                }
            }
            DefenderBlockBreakTickResult.IN_PROGRESS -> Unit
        }
        return FlagBreakingOutcome.CONTINUE_MINING
    }

    fun updateDefenderWeapon(
        session: DefenseSession,
        defender: AiDefender,
        decision: DefenderDecision,
        targetPlayer: Player?,
        targets: List<Player>,
        now: Long,
    ) {
        val reloadWasPending = defender.weapon.reloadPending
        defender.weapon.updateReload(now)
        if (reloadWasPending || targetPlayer == null || !decision.wantsToFire) return
        if (!defender.weapon.canFire(now)) return
        val entity = defender.entity
        val gun = defender.weapon.gun
        val targetsInRange = targets.filter { entity.getDistanceSquared(it) <= gun.maxRange * gun.maxRange }
        if (targetPlayer !in targetsInRange) return
        val targetPosition = Vehicle.protectedVehicleAimPosition(targetPlayer)
            ?: targetPlayer.position.add(0.0, targetPlayer.eyeHeight, 0.0)
        if (!hasClearShot(entity, targetPosition, session.instance)) return
        if (!playerLikeAimAligned(entity.position, targetPosition, 12F, 10F)) return

        entity.lookAt(targetPosition)
        gun.fireFromEntity(entity, targetPosition, targetsInRange)
        defender.weapon.recordShot(now)
    }

    private fun beginFlagBreaking(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
    ) {
        defender.navigation.cancel()
        defender.blockBreaker.begin(
            instance = session.instance,
            kind = DefenderBlockBreakKind.FLAG,
            attack = attack,
            position = attack.flagBlock,
        )
        defender.navigation.retainCurrentChunk()
        sessions.reconcileChunks(session)
    }

    fun defenderDecision(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack?,
        targets: List<Player>,
        now: Long,
    ): DefenderDecision {
        if (now - defender.lastDecisionAt < DEFENDER_DECISION_MILLIS) {
            defender.lastDecision?.let { return it.copy(wantsToFire = false) }
        }
        val objective = campaignObjective(defender, attack)
        val defenderPosition = defender.entity.position.toDefenderPoint()
        val objectivePosition = objective.toDefenderPoint()
        val observation = DefenderObservation(
            nowMillis = now,
            position = defenderPosition,
            objectivePosition = objectivePosition,
            targets = targets.mapNotNull { player ->
                val targetPosition = player.position.toDefenderPoint()
                if (!defender.brain.isPotentialTarget(defenderPosition, objectivePosition, targetPosition)) {
                    return@mapNotNull null
                }
                val aimPosition = Vehicle.protectedVehicleAimPosition(player)
                    ?: player.position.add(0.0, player.eyeHeight, 0.0)
                DefenderTargetObservation(
                    id = player.uuid,
                    position = targetPosition,
                    visible = hasClearShot(defender.entity, aimPosition, session.instance),
                )
            },
            weaponRange = defender.weapon.gun.maxRange,
            canMineObjective = attack != null &&
                defender.entity.position.add(0.0, defender.entity.eyeHeight, 0.0)
                    .distanceSquared(objective) <= FLAG_REACH * FLAG_REACH &&
                hasClearFlagReach(defender.entity, objective, session.instance),
        )
        val decision = defender.brain.decide(observation, defender.brainMemory)
        defender.brainMemory = decision.memory
        defender.lastDecisionAt = now
        defender.lastDecision = decision
        return decision
    }
}
