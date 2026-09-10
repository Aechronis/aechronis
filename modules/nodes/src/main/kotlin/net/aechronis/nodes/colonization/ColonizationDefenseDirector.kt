package net.aechronis.nodes.colonization

import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.Attack
import net.aechronis.nodes.war.AttackMode
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val DEFENDER_TICK_INTERVAL = 1
private const val CHUNK_LOAD_DRAIN_TIMEOUT_SECONDS = 60L

/** Runs defense sessions in tick order; campaign policy remains with [Colonization]. */
internal class ColonizationDefenseDirector(
    private val pruneCampaign: (DefenseSession) -> Unit,
    private val hasSelectedCampaign: (Town) -> Boolean,
    private val campaignAttackingTowns: (DefenseSession) -> Set<Town>,
) {
    private val sessions = DefenseSessions()
    private val spawner = DefenderSpawner(sessions)
    private val movement = DefenderMovementController(sessions)
    private val combat = DefenderCombat(sessions)
    private var defenseDirectorTask: Task? = null
    private var directorSessionOffset = 0

    fun onAttackStarted(attack: Attack) {
        if (attack.mode != AttackMode.COLONIZATION) return

        val targetTown = attack.targetTown ?: return
        if (attack.targetTerritory.town !== targetTown) return
        if (!targetTown.isAi) return
        val session = sessions.find(targetTown.uuid)
        if (session == null) {
            System.err.println(
                "[Nodes] Cancelled colonization at ${attack.flagBase}: ${targetTown.name} has no active defenders",
            )
            attack.cancel()
            return
        }
        if (attack in session.attacks) return
        session.attacks.add(attack)
    }

    fun onAttackEnded(attack: Attack): Boolean {
        val targetTown = attack.targetTown ?: return false
        val session = sessions.find(targetTown.uuid) ?: return false
        if (!session.attacks.remove(attack)) return false
        activeDefenders(session).forEach { defender ->
            if (defender.blockBreaker.attack === attack) defender.blockBreaker.stop(session.instance)
            // the objective has changed to another flag or back to the town guard position
            // so do not let the short decision cache send a persistent defender toward the ended attack
            defender.lastDecisionAt = 0
            defender.lastDecision = null
            if (defender.navigation.references(attack)) defender.navigation.cancel()
        }
        sessions.reconcileChunks(session)
        return true
    }

    fun startDefenseSessionIfNeeded(
        targetTown: Town,
        respawnDelayMillis: () -> Long,
    ): Result<Unit> {
        if (sessions.find(targetTown.uuid) != null) return Result.success(Unit)
        val config = targetTown.aiConfig
        if (!config.configured) {
            return Result.failure(IllegalStateException("${targetTown.name} has no AI defenders configured"))
        }
        if (!config.enabled) {
            return Result.failure(
                IllegalStateException("${targetTown.name} cannot defend because a configured gun is unavailable"),
            )
        }
        val configSnapshot = config.copy(guns = config.guns.toList())
        val guns = configSnapshot.guns.map { name ->
            val item = Item.getFromName(name)
            if (item is Gun) {
                item
            } else {
                return Result.failure(
                    IllegalStateException("${targetTown.name} references unavailable gun '$name'"),
                )
            }
        }
        if (guns.isEmpty()) {
            return Result.failure(IllegalStateException("${targetTown.name} cannot deploy defenders without a gun"))
        }
        val instance = MinecraftServer.getInstanceManager().instances.firstOrNull()
            ?: return Result.failure(IllegalStateException("The colonization world is unavailable"))

        val slots = List(configSnapshot.enemyCount) { index -> AiDefenderSlot(guns[index % guns.size]) }
        val session = DefenseSession(
            instance = instance,
            targetTown = targetTown,
            slots = slots,
            respawnDelayMillis = respawnDelayMillis,
            spawnCenter = campaignSpawnCenter(targetTown),
        )
        sessions.add(session)
        ensureDefenseDirector()
        ModuleScheduler.scheduleNextTick {
            if (sessions.isActive(session)) spawner.prepareDueDefenderSpawns(session, System.currentTimeMillis())
        }
        return Result.success(Unit)
    }

    private fun campaignSpawnCenter(targetTown: Town): Pos {
        val configured = targetTown.spawnpoint
        val configuredTerritory = Territory.fromBlock(configured.blockX(), configured.blockZ())
        if (configuredTerritory?.town === targetTown && configuredTerritory.id == targetTown.home) return configured

        val home = Territory.fromId(targetTown.home) ?: return configured
        return Pos(
            home.core.x * 16 + 8.5,
            configured.y,
            home.core.z * 16 + 8.5,
        )
    }

    private fun ensureDefenseDirector() {
        if (defenseDirectorTask != null) return
        defenseDirectorTask = ModuleScheduler
            .buildTask(::tickDefenseSessions)
            .delay(TaskSchedule.tick(DEFENDER_TICK_INTERVAL))
            .repeat(TaskSchedule.tick(DEFENDER_TICK_INTERVAL))
            .schedule()
    }

    private fun tickDefenseSessions() {
        movement.beginTick()
        val activeSessions = sessions.all()
        if (activeSessions.isNotEmpty()) {
            val offset = Math.floorMod(directorSessionOffset, activeSessions.size)
            repeat(activeSessions.size) { index -> tickDefense(activeSessions[(offset + index) % activeSessions.size]) }
            directorSessionOffset = (offset + 1) % activeSessions.size
        } else {
            defenseDirectorTask?.cancel()
            defenseDirectorTask = null
        }
    }

    fun hasSession(targetTown: Town): Boolean = sessions.find(targetTown.uuid)?.targetTown === targetTown

    fun endIfNoAttacks(targetTownId: UUID) {
        val session = sessions.find(targetTownId) ?: return
        if (session.attacks.none(::isAttackActive)) sessions.end(session)
    }

    fun drainForReload() {
        try {
            sessions.prepareForShutdown(CHUNK_LOAD_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for automatic chunk loads before world reload", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "Automatic chunk loads did not finish within $CHUNK_LOAD_DRAIN_TIMEOUT_SECONDS seconds before world reload",
                error,
            )
        }
    }

    /** Ends sessions and renews their resources after [drainForReload] has succeeded. */
    fun resetAfterDrain() {
        cleanupState()
        sessions.resetChunkLeases()
    }

    fun prepareForShutdown(timeout: Long, unit: TimeUnit) {
        defenseDirectorTask?.cancel()
        defenseDirectorTask = null
        sessions.prepareForShutdown(timeout, unit)
    }

    fun cleanup() {
        cleanupState()
        sessions.shutdown()
    }

    private fun cleanupState() {
        sessions.clear()
        defenseDirectorTask?.cancel()
        defenseDirectorTask = null
        movement.reset()
        directorSessionOffset = 0
    }

    fun acceptsAutomaticChunkLeases(): Boolean = sessions.acceptsAutomaticChunkLeases()

    private fun tickDefense(session: DefenseSession) {
        if (!sessions.isActive(session)) return
        pruneCampaign(session)
        if (!sessions.isActive(session)) return
        if (!hasSelectedCampaign(session.targetTown) && session.attacks.none(::isAttackActive)) {
            sessions.end(session)
            return
        }
        val instance = session.instance
        val attackingTowns = campaignAttackingTowns(session)
        val targets = instance.players
            .filter { player -> isValidNavigationTarget(player, attackingTowns, instance) }
        val now = System.currentTimeMillis()
        spawner.retireDeadDefenders(session, now)
        spawner.prepareDueDefenderSpawns(session, now)

        movement.applyPreparedNavigationPaths(session, now)
        for (defender in activeDefenders(session)) {
            val entity = defender.entity
            if (!defender.ready || entity.instance !== instance) continue

            val attack = closestActiveAttack(session, entity.position)
            val flagPosition = attack?.flagBlock?.add(0.5, 0.5, 0.5)?.asPos()
            val flagReachOrigin = entity.position.add(0.0, entity.eyeHeight, 0.0)
            val distanceToFlag = flagPosition?.let(flagReachOrigin::distanceSquared)
            val decision = combat.defenderDecision(session, defender, attack, targets, now)
            val targetPlayer = decision.targetId?.let { targetId -> targets.firstOrNull { it.uuid == targetId } }
            combat.updateDefenderAim(defender, decision, targetPlayer)
            if (
                decision.mineObjective &&
                !defender.weapon.reloadPending &&
                attack != null &&
                flagPosition != null &&
                distanceToFlag != null &&
                distanceToFlag <= FLAG_REACH * FLAG_REACH &&
                hasClearFlagReach(entity, flagPosition, instance)
            ) {
                when (combat.tickFlagBreaking(session, defender, attack, now)) {
                    FlagBreakingOutcome.ATTACK_CANCELLED -> return
                    FlagBreakingOutcome.CONTINUE_MINING -> continue
                }
            } else if (defender.blockBreaker.kind == DefenderBlockBreakKind.FLAG) {
                defender.blockBreaker.stop(instance)
            }

            val movementOutcome = movement.moveDefender(session, defender, attack, decision, targetPlayer, now)
            if (movementOutcome == DefenderMovementOutcome.RECOVERING_TERRAIN) continue
            combat.updateDefenderWeapon(session, defender, decision, targetPlayer, targets, now)
        }
    }

    private fun isAttackingParticipant(
        player: Player,
        attackingTowns: Set<Town>,
    ): Boolean = attackingTowns.any { attackingTown -> Town.areAllied(Town.fromPlayer(player), attackingTown) }

    private fun isValidNavigationTarget(
        player: Player,
        attackingTowns: Set<Town>,
        instance: Instance,
    ): Boolean = player.instance === instance &&
        isAttackingParticipant(player, attackingTowns) &&
        player.gameMode != GameMode.SPECTATOR &&
        !player.isDead &&
        !player.isRemoved
}
