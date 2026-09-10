package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.war.Attack
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.block.Block
import kotlin.math.abs

/** Breaks or places terrain when a defender cannot reach an active flag by walking. */
internal object DefenderTerrainRecovery {
    fun stopTerrainRecovery(
        session: DefenseSession,
        defender: AiDefender,
    ) {
        if (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN) {
            defender.blockBreaker.stop(session.instance)
        }
        defender.navigation.clearTerrainRecovery()
    }

    fun recoverFlagRouteTerrain(
        session: DefenseSession,
        defender: AiDefender,
        goal: NavigationGoal,
        now: Long,
    ): Boolean {
        val attack = goal.attack ?: return false
        if (!isAttackActive(attack)) {
            if (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN) {
                defender.blockBreaker.stop(session.instance)
            }
            defender.navigation.clearTerrainRecovery()
            return false
        }
        if (!defender.entity.isOnGround) {
            if (defender.navigation.airborneRecoveryDue(now)) {
                defender.entity.velocity = defender.entity.velocity.withY(0.0)
                defender.navigation.restartForTerrainRecovery(now)
            }
            return true
        }

        if (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN) {
            return tickTerrainBreaking(session, defender, attack, now)
        }
        val protectedBlocks = sessionProtectedBlocks(session)
        val canEdit: (BlockVec) -> Boolean = { position ->
            isTerrainEditable(session, position, protectedBlocks)
        }
        val recoveryTargets = buildList {
            add(goal.position)
            flagApproachCandidates(session.instance, attack, defender.entity.eyeHeight, FLAG_REACH.toDouble())
                .asSequence()
                .filter { position -> position.blockY() > defender.entity.position.blockY() }
                .sortedWith(
                    compareBy<Pos> { position -> abs(position.y - attack.flagBase.blockY) }
                        .thenBy(defender.entity.position::distanceSquared),
                ).forEach(::add)
        }.distinctBy { position -> Triple(position.blockX(), position.blockY(), position.blockZ()) }
        val edit = recoveryTargets.firstNotNullOfOrNull { target ->
            aiTerrainEdit(
                defender.entity.position,
                target,
                blockAt = session.instance::getBlock,
                canEdit = canEdit,
            )
        }
        if (edit == null) {
            defender.navigation.advanceApproach()
            defender.navigation.clearTerrainRecovery()
            defender.navigation.cancel()
            return false
        }

        return when (edit.kind) {
            AiTerrainEditKind.BREAK -> {
                beginTerrainBreaking(session, defender, attack, edit.position)
                true
            }
            AiTerrainEditKind.PLACE -> {
                session.instance.setBlock(edit.position, Block.COBBLESTONE)
                defender.entity.lookAt(edit.position.add(0.5, 0.5, 0.5))
                defender.entity.swingMainHand()
                defender.navigation.clearTerrainRecovery()
                defender.navigation.navigateOntoPlacedTerrain(goal, edit.position, now)
                true
            }
        }
    }

    private fun beginTerrainBreaking(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
        position: BlockVec,
    ) {
        defender.blockBreaker.begin(
            instance = session.instance,
            kind = DefenderBlockBreakKind.TERRAIN,
            attack = attack,
            position = position,
        )
    }

    private fun tickTerrainBreaking(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
        now: Long,
    ): Boolean {
        val position = defender.blockBreaker.position ?: return false
        if (defender.blockBreaker.attack !== attack) {
            defender.blockBreaker.stop(session.instance)
            defender.navigation.requestTerrainRecovery()
            return false
        }
        val protectedBlocks = sessionProtectedBlocks(session)
        return when (
            defender.blockBreaker.tick(
                instance = session.instance,
                nowMillis = now,
                restartWhenBlockChanges = false,
                canContinue = { candidate, _ -> isTerrainEditable(session, candidate, protectedBlocks) },
            )
        ) {
            DefenderBlockBreakTickResult.IN_PROGRESS -> true
            DefenderBlockBreakTickResult.COMPLETED -> {
                session.instance.setBlock(position, Block.AIR)
                defender.navigation.clearTerrainRecovery()
                defender.navigation.cancel()
                true
            }
            DefenderBlockBreakTickResult.INVALID -> {
                defender.navigation.requestTerrainRecovery()
                false
            }
        }
    }

    private fun sessionProtectedBlocks(session: DefenseSession): Set<BlockVec> = session.attacks
        .asSequence()
        .filter(::isAttackActive)
        .flatMap { attack -> attackProtectedBlocks(attack).asSequence() }
        .toSet()

    private fun isTerrainEditable(
        session: DefenseSession,
        position: BlockVec,
        protectedBlocks: Set<BlockVec> = sessionProtectedBlocks(session),
    ): Boolean = position !in protectedBlocks &&
        !isActiveFlagColumn(session, position) &&
        session.instance.worldBorder.inBounds(position) &&
        session.instance.isChunkLoaded(position) &&
        Territory.fromBlock(position.blockX(), position.blockZ())?.town === session.targetTown

    private fun isActiveFlagColumn(
        session: DefenseSession,
        position: BlockVec,
    ): Boolean = session.attacks.any { attack ->
        isAttackActive(attack) &&
            position.blockX() == attack.flagBase.blockX &&
            position.blockZ() == attack.flagBase.blockZ
    }
}
