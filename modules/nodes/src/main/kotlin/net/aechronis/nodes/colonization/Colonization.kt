package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.Attack
import net.aechronis.nodes.war.AttackMode
import net.aechronis.nodes.war.FlagWar
import net.minestom.server.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Owns player campaign selections and authorization; delegates defender execution and lifecycle. */
object Colonization {
    private data class Selection(
        val attackingTown: Town,
        val targetTown: Town,
    )

    private val selections = ConcurrentHashMap<UUID, Selection>()
    private val defenses = ColonizationDefenseDirector(
        pruneCampaign = ::pruneInvalidCampaignState,
        hasSelectedCampaign = { targetTown -> selections.values.any { it.targetTown === targetTown } },
        campaignAttackingTowns = ::campaignAttackingTowns,
    )

    fun selectTarget(
        player: Player,
        target: Town,
    ): Result<Town> {
        val attackingTown = Resident.fromPlayer(player)?.town
            ?: return Result.failure(IllegalStateException("You must be in a town to colonize"))
        return selectTarget(player.uuid, attackingTown, target)
    }

    internal fun selectTarget(
        attacker: UUID,
        attackingTown: Town,
        target: Town,
        respawnDelayMillis: () -> Long = { randomDefenderRespawnDelayMillis() },
    ): Result<Town> {
        val attackingNation = attackingTown.nation
            ?: return Result.failure(IllegalStateException("You must be in a nation to colonize"))
        if (!canStartColonization(Resident.fromUuid(attacker), attackingTown)) {
            clearSelection(attacker)
            return Result.failure(
                IllegalStateException("You must be a town officer or town leader in your nation to colonize"),
            )
        }
        val nation = target.nation
            ?: return Result.failure(IllegalArgumentException("${target.name} is not part of a nation"))
        if (!target.isAi) return Result.failure(IllegalArgumentException("${target.name} is not an AI town"))
        if (target === attackingTown || nation === attackingNation) {
            return Result.failure(IllegalArgumentException("You cannot colonize your own nation"))
        }
        if (colonizationAccess(attackingNation, target) == null) {
            return Result.failure(
                IllegalArgumentException(
                    "${target.name} must border ${attackingNation.name} or be within range of one of its ports",
                ),
            )
        }

        val selection = Selection(attackingTown, target)
        val previousSelection = selections.put(attacker, selection)
        val defenseStarted = defenses.startDefenseSessionIfNeeded(target, respawnDelayMillis)
        if (defenseStarted.isFailure) {
            if (previousSelection != null) {
                selections.replace(attacker, selection, previousSelection)
            } else {
                selections.remove(attacker, selection)
            }
            return Result.failure(defenseStarted.exceptionOrNull()!!)
        }
        if (previousSelection != null && previousSelection != selection) stopSelection(attacker, previousSelection)
        return Result.success(target)
    }

    fun clearSelection(player: Player) {
        clearSelection(player.uuid)
    }

    internal fun clearSelection(attacker: UUID) {
        val selection = selections.remove(attacker) ?: return
        stopSelection(attacker, selection)
    }

    private fun stopSelection(
        attacker: UUID,
        selection: Selection,
    ) {
        val campaignStillSelected = selections
            .filterKeys { selectedAttacker -> selectedAttacker != attacker }
            .values
            .any { other ->
                other.attackingTown === selection.attackingTown && other.targetTown === selection.targetTown
            }
        FlagWar.stopColonizationCampaign(
            attacker,
            selection.attackingTown,
            selection.targetTown,
            abandonCompletedProgress = !campaignStillSelected,
        )
        endDefenseSessionIfInactive(selection.targetTown.uuid)
    }

    fun selectedTown(player: Player): Town? = selections[player.uuid]?.targetTown?.takeIf(Town::isAi)

    internal fun canSelectTarget(
        attackingTown: Town,
        targetTown: Town,
    ): Boolean {
        val attackingNation = attackingTown.nation ?: return false
        val targetNation = targetTown.nation ?: return false
        return targetTown.isAi &&
            targetTown !== attackingTown &&
            targetNation !== attackingNation &&
            colonizationAccess(attackingNation, targetTown) != null
    }

    internal fun isAuthorized(
        attacker: UUID,
        attackingTown: Town,
        targetTown: Town?,
    ): Boolean {
        if (targetTown == null || !canSelectTarget(attackingTown, targetTown)) return false
        if (!canStartColonization(Resident.fromUuid(attacker), attackingTown)) return false
        val selection = selections[attacker]
        return selection?.attackingTown === attackingTown &&
            selection.targetTown === targetTown &&
            defenses.hasSession(targetTown)
    }

    internal fun attackRemainsAuthorized(attack: Attack): Boolean {
        if (attack.mode != AttackMode.COLONIZATION) return true
        val targetTown = attack.targetTown ?: return false
        if (attack.targetTerritory.town !== targetTown) return false
        if (!defenses.hasSession(targetTown)) return false
        val attackingNation = attack.town.nation ?: return false
        return Town.fromName(attack.town.name) === attack.town &&
            Town.fromName(targetTown.name) === targetTown &&
            targetTown.isAi &&
            targetTown !== attack.town &&
            targetTown.nation !== attackingNation &&
            Resident.fromUuid(attack.attacker)?.town === attack.town
    }

    internal fun onAttackStarted(attack: Attack) = defenses.onAttackStarted(attack)

    internal fun onAttackEnded(attack: Attack) {
        if (defenses.onAttackEnded(attack)) {
            attack.targetTown?.let { endDefenseSessionIfInactive(it.uuid) }
        }
    }

    private fun endDefenseSessionIfInactive(targetTownId: UUID) {
        if (selections.values.none { selection -> selection.targetTown.uuid == targetTownId }) {
            defenses.endIfNoAttacks(targetTownId)
        }
    }

    internal fun resetForReload() {
        // Keep campaign state intact if outstanding chunk loads cannot be drained.
        defenses.drainForReload()
        selections.clear()
        defenses.resetAfterDrain()
    }

    internal fun prepareForShutdown(timeout: Long, unit: TimeUnit) = defenses.prepareForShutdown(timeout, unit)

    internal fun cleanup() {
        selections.clear()
        defenses.cleanup()
    }

    internal fun acceptsAutomaticChunkLeases(): Boolean = defenses.acceptsAutomaticChunkLeases()

    private fun pruneInvalidCampaignState(session: DefenseSession) {
        val targetTown = session.targetTown
        val targetValid = Town.fromName(targetTown.name) === targetTown && targetTown.isAi
        selections.entries
            .filter { (attacker, selection) ->
                selection.targetTown === targetTown &&
                    (!targetValid || !selectionRemainsAuthorized(attacker, selection))
            }.map { (attacker) -> attacker }
            .forEach(::clearSelection)

        session.attacks.toList().forEach { attack ->
            when {
                !isAttackActive(attack) -> session.attacks.remove(attack)
                !targetValid || !attackRemainsAuthorized(attack) -> attack.cancel()
            }
        }
    }

    private fun selectionRemainsAuthorized(
        attacker: UUID,
        selection: Selection,
    ): Boolean {
        val attackingTown = selection.attackingTown
        val targetTown = selection.targetTown
        return Town.fromName(attackingTown.name) === attackingTown &&
            Resident.fromUuid(attacker)?.town === attackingTown &&
            targetTown !== attackingTown &&
            targetTown.nation !== attackingTown.nation
    }

    private fun campaignAttackingTowns(session: DefenseSession): Set<Town> = buildSet {
        selections.forEach { (_, selection) ->
            if (selection.targetTown === session.targetTown) add(selection.attackingTown)
        }
        session.attacks.filter(::isAttackActive).mapTo(this, Attack::town)
    }
}
