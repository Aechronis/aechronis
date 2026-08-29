package net.aechronis.nodes.colonization

import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.utils.Ray
import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Attack
import net.aechronis.nodes.war.AttackMode
import net.aechronis.nodes.war.FlagWar
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.utils.EntityTags
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerSkin
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.player.ResolvableProfile
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

private const val DEFENDER_TICK_INTERVAL = 1
private const val FLAG_REACH = 3
private const val DEFENDER_DECISION_MILLIS = 100L
private const val NAVIGATION_SEGMENT_DISTANCE = 64.0
private const val NAVIGATION_ROUTE_SAMPLE_DISTANCE = 8.0
private const val MAX_NAVIGATION_ROUTE_CHUNKS = 96
private const val MAX_PATH_STARTS_PER_TICK = 1
private const val MAX_PATH_PREPARATIONS_PER_TICK = 2
private const val MAX_DEFENDER_SPAWNS_PER_WAVE = 4
private const val MAX_LOCAL_SPAWN_COLUMNS = 96
private const val MIN_RANDOM_GUARD_COLUMNS = 32
private const val RANDOM_GUARD_COLUMNS_PER_DEFENDER = 8
private const val MIN_LOCAL_SPAWN_RADIUS = 8
private const val MAX_LOCAL_SPAWN_RADIUS = 20
private const val LOCAL_SPAWN_VERTICAL_SEARCH = 12
private const val CHUNK_LOAD_DRAIN_TIMEOUT_SECONDS = 60L
internal const val MAX_DEFENDER_RESPAWN_DELAY_MILLIS = 60_000L
private const val AI_DEFENDER_SKIN_TEXTURES =
    "ewogICJ0aW1lc3RhbXAiIDogMTc4NjQ3ODMyNzM5NiwKICAicHJvZmlsZUlkIiA6ICJjMmFlZGUzN2M3MDQ0ZWFmOWJkYWYy" +
        "N2U2N2ZhZWRiZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJKb25hc01hbm5lbjQ3IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDog" +
        "dHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWlu" +
        "ZWNyYWZ0Lm5ldC90ZXh0dXJlL2U4MzMyNTQ3YWIwNjZlZWUyMWI5NTZhNDczZmNiZTA3MGY5OWJiOWQ2MGMxN2EzNzVlOTNh" +
        "ZWY2MGVlNjYyZGMiCiAgICB9CiAgfQp9"
private const val AI_DEFENDER_SKIN_SIGNATURE =
    "yXgHlIfkgQ89nu1LFIvhdQZtXxHC3TXznyRXEqGUVU0appvrnC7TdmMD5B+btO5TkRjpQOQi838c7STlGCA4gyfflSr9O+jb" +
        "ywQ6oNVVd/rhvI7pPn9QeA442eI1nnYNOH7qmoxIvYJmsfqdPAq8jm5or2Y6/TpP3ihwrW2smQl73Nagib4rHu1qkQesNS/g" +
        "2iB0xkCbTeA+nkfPY+JejgPx8qqF9YiQl2k3IOi97zEnkve/HkWIgPGDrIOAQ6SrJEuMJsRYuDBrdPks2jHi5ffbguv86q58" +
        "Mimup8FTPwPDdaAMo1Xapd7/3apspg9GCESB2yqao1KFtwtxli8nYQ0NmKdldGAEWjLHQ6FNc0tPJK0tW84PtKieFDT39oNZ" +
        "zBHufwhoV8SlAG5OUhLxz/vhNAXLKTRSmayWbRSuaPYOwa6E9OACeoNYC9qcZeRlNZ4pvpG8/YcSw1/qZdQOzf8y64K3lN1z" +
        "IGsZIDkmihnjVCJcJZ0T3OzeuBfUcLm3kTc8igF4TlfcOCfwZXz/YoqBWzkVgJYRL51oHcSaA/Fw+nDIzIQrfNE9Q+5e4d+l" +
        "+Wx6Ee0MzlfJ5rzhisxbSj1JgV1HT/SSPoN4GMXOXHu3byFudGi+BZM3zHZS7SKVg9qKPnF85I8ZNV6JwsXsb3MO/IhrtMvo" +
        "irRSKV30HbE="

internal val AI_DEFENDER_PROFILE = ResolvableProfile(
    PlayerSkin(AI_DEFENDER_SKIN_TEXTURES, AI_DEFENDER_SKIN_SIGNATURE),
)

/** Player-scoped AI-town targeting and the persistent defenders for each active colonization campaign. */
object Colonization {
    private data class Selection(
        val attackingTown: Town,
        val targetTown: Town,
    )

    private val selections = ConcurrentHashMap<UUID, Selection>()
    private val defenseSessions = HashMap<UUID, DefenseSession>()
    private var chunkLeaseManager = AutomaticChunkLeaseManager()
    private var defenseDirectorTask: Task? = null
    private var pathStartsRemaining: Int = 0
    private var pathPreparationsRemaining: Int = 0
    private var directorSessionOffset: Int = 0
    private val diamondPickaxe: ItemStack = ItemStack.of(Material.DIAMOND_PICKAXE)

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
        val defenseStarted = startDefenseSessionIfNeeded(target, respawnDelayMillis)
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
            defenseSessions[targetTown.uuid]?.targetTown === targetTown
    }

    internal fun attackRemainsAuthorized(attack: Attack): Boolean {
        if (attack.mode != AttackMode.COLONIZATION) return true
        val targetTown = attack.targetTown ?: return false
        if (attack.targetTerritory.town !== targetTown) return false
        if (defenseSessions[targetTown.uuid]?.targetTown !== targetTown) return false
        val attackingNation = attack.town.nation ?: return false
        return Nodes.towns[attack.town.name] === attack.town &&
            Nodes.towns[targetTown.name] === targetTown &&
            targetTown.isAi &&
            targetTown !== attack.town &&
            targetTown.nation !== attackingNation &&
            Resident.fromUuid(attack.attacker)?.town === attack.town
    }

    internal fun onAttackStarted(attack: Attack) {
        if (attack.mode != AttackMode.COLONIZATION) return

        val targetTown = attack.targetTown ?: return
        if (attack.targetTerritory.town !== targetTown) return
        if (!targetTown.isAi) return
        val session = defenseSessions[targetTown.uuid]
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

    internal fun onAttackEnded(attack: Attack) {
        val targetTown = attack.targetTown ?: return
        val session = defenseSessions[targetTown.uuid] ?: return
        if (!session.attacks.remove(attack)) return
        activeDefenders(session).forEach { defender ->
            if (defender.blockBreaker.attack === attack) defender.blockBreaker.stop(session.instance)
            // the objective has changed to another flag or back to the town guard position
            // so do not let the short decision cache send a persistent defender toward the ended attack
            defender.lastDecisionAt = 0
            defender.lastDecision = null
            if (defender.navigation.references(attack)) defender.navigation.cancel()
        }
        reconcileSessionChunkLeases(session)
        endDefenseSessionIfInactive(targetTown.uuid)
    }

    private fun startDefenseSessionIfNeeded(
        targetTown: Town,
        respawnDelayMillis: () -> Long,
    ): Result<Unit> {
        if (defenseSessions.containsKey(targetTown.uuid)) return Result.success(Unit)
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
        defenseSessions[targetTown.uuid] = session
        ensureDefenseDirector()
        ModuleScheduler.scheduleNextTick {
            if (isDefenseSessionActive(session)) prepareDueDefenderSpawns(session, System.currentTimeMillis())
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
        pathStartsRemaining = MAX_PATH_STARTS_PER_TICK
        pathPreparationsRemaining = MAX_PATH_PREPARATIONS_PER_TICK
        val sessions = defenseSessions.values.toList()
        if (sessions.isNotEmpty()) {
            val offset = Math.floorMod(directorSessionOffset, sessions.size)
            repeat(sessions.size) { index -> tickDefense(sessions[(offset + index) % sessions.size]) }
            directorSessionOffset = (offset + 1) % sessions.size
        } else {
            defenseDirectorTask?.cancel()
            defenseDirectorTask = null
        }
    }

    private fun endDefenseSessionIfInactive(targetTownId: UUID) {
        val session = defenseSessions[targetTownId] ?: return
        val selected = selections.values.any { selection -> selection.targetTown.uuid == targetTownId }
        val activeAttack = session.attacks.any(::isAttackActive)
        if (!selected && !activeAttack) endDefenseSession(session)
    }

    private fun endDefenseSession(session: DefenseSession) {
        if (!defenseSessions.remove(session.targetTown.uuid, session)) return
        session.slots.forEach { slot ->
            slot.generation += 1
            slot.spawnPending = false
            slot.defender?.let { defender ->
                defender.blockBreaker.stop(session.instance)
                defender.navigation.cancel()
                if (!defender.entity.isRemoved) defender.entity.remove()
            }
            slot.defender = null
        }
        session.spawnPreparationChunks.clear()
        chunkLeaseManager.release(session)
    }

    internal fun resetForReload() {
        try {
            chunkLeaseManager.prepareForShutdown(CHUNK_LOAD_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for automatic chunk loads before world reload", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "Automatic chunk loads did not finish within $CHUNK_LOAD_DRAIN_TIMEOUT_SECONDS seconds before world reload",
                error,
            )
        }
        cleanupState()
        chunkLeaseManager.shutdown()
        chunkLeaseManager = AutomaticChunkLeaseManager()
    }

    internal fun prepareForShutdown(
        timeout: Long,
        unit: TimeUnit,
    ) {
        defenseDirectorTask?.cancel()
        defenseDirectorTask = null
        chunkLeaseManager.prepareForShutdown(timeout, unit)
    }

    internal fun cleanup() {
        cleanupState()
        chunkLeaseManager.shutdown()
    }

    private fun cleanupState() {
        selections.clear()
        defenseSessions.values.toList().forEach(::endDefenseSession)
        defenseDirectorTask?.cancel()
        defenseDirectorTask = null
        pathStartsRemaining = 0
        pathPreparationsRemaining = 0
        directorSessionOffset = 0
    }

    internal fun acceptsAutomaticChunkLeases(): Boolean = chunkLeaseManager.isOpen()

    private fun activeDefenders(session: DefenseSession): List<AiDefender> = session.slots
        .mapNotNull(AiDefenderSlot::defender)

    private fun tickDefense(session: DefenseSession) {
        if (!isDefenseSessionActive(session)) return
        pruneInvalidCampaignState(session)
        if (!isDefenseSessionActive(session)) return
        if (selections.values.none { selection -> selection.targetTown === session.targetTown } && session.attacks.none(::isAttackActive)) {
            endDefenseSession(session)
            return
        }
        val instance = session.instance
        val attackingTowns = campaignAttackingTowns(session)
        val targets = instance.players
            .filter { player -> isValidNavigationTarget(player, attackingTowns, instance) }
        val now = System.currentTimeMillis()
        retireDeadDefenders(session, now)
        prepareDueDefenderSpawns(session, now)

        applyPreparedNavigationPaths(session, now)
        for (defender in activeDefenders(session)) {
            val entity = defender.entity
            if (!defender.ready || entity.instance !== instance) continue

            val attack = closestActiveAttack(session, entity.position)
            val flagPosition = attack?.flagBlock?.add(0.5, 0.5, 0.5)?.asPos()
            val flagReachOrigin = entity.position.add(0.0, entity.eyeHeight, 0.0)
            val distanceToFlag = flagPosition?.let(flagReachOrigin::distanceSquared)
            val decision = defenderDecision(session, defender, attack, targets, now)
            val targetPlayer = decision.targetId?.let { targetId -> targets.firstOrNull { it.uuid == targetId } }
            defender.motor.lookTarget = targetPlayer
                ?.takeIf { decision.memory.targetWasVisible }
                ?.let { player ->
                    Vehicle.protectedVehicleAimPosition(player)
                        ?: player.position.add(0.0, player.eyeHeight, 0.0)
                } ?: decision.aimPosition?.toPos()
            if (
                decision.mineObjective &&
                !defender.weapon.reloadPending &&
                attack != null &&
                flagPosition != null &&
                distanceToFlag != null &&
                distanceToFlag <= FLAG_REACH * FLAG_REACH &&
                hasClearFlagReach(entity, flagPosition, instance)
            ) {
                if (
                    defender.blockBreaker.kind != DefenderBlockBreakKind.FLAG ||
                    defender.blockBreaker.attack !== attack
                ) {
                    beginFlagBreaking(session, defender, attack)
                }
                when (
                    defender.blockBreaker.tick(
                        instance = instance,
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
                        return
                    }
                    DefenderBlockBreakTickResult.INVALID -> {
                        if (instance.getBlock(attack.flagBlock).isAir) {
                            attack.cancel()
                            return
                        }
                    }
                    DefenderBlockBreakTickResult.IN_PROGRESS -> Unit
                }
                continue
            } else if (defender.blockBreaker.kind == DefenderBlockBreakKind.FLAG) {
                defender.blockBreaker.stop(instance)
            }

            val directCombatTarget = when (decision.movement) {
                DefenderMovement.RETREAT_FROM_TARGET,
                DefenderMovement.STRAFE_AROUND_TARGET,
                -> targetPlayer

                else -> null
            }
            val navigationGoal = navigationGoalFor(session, defender, attack, decision, targetPlayer)
            if (directCombatTarget != null) {
                if (defender.navigation.hasGoal) {
                    defender.navigation.cancel()
                    defender.navigation.retainCurrentChunk()
                    reconcileSessionChunkLeases(session)
                }
                val lookAt = Vehicle.protectedVehicleAimPosition(directCombatTarget)
                    ?: directCombatTarget.position.add(0.0, directCombatTarget.eyeHeight, 0.0)
                val movementTarget = safeDirectCombatMovementTarget(
                    session = session,
                    defender = defender,
                    attack = attack,
                    targetPosition = directCombatTarget.position,
                    decision = decision,
                )
                if (movementTarget != null) {
                    defender.motor.moveTowards(movementTarget, 0.28, lookAt)
                } else {
                    defender.motor.lookTowards(lookAt)
                }
            } else if (navigationGoal != null) {
                if (navigationGoal.attack == null) {
                    if (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN) {
                        defender.blockBreaker.stop(instance)
                    }
                    defender.navigation.clearTerrainRecovery()
                }
                defender.navigation.discardStaleGoal(navigationGoal)
                if (
                    navigationGoal.attack != null &&
                    (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN || defender.navigation.recoveryRequested) &&
                    recoverFlagRouteTerrain(session, defender, navigationGoal, now)
                ) {
                    continue
                }
                if (pathPreparationsRemaining > 0 && defender.navigation.shouldRequestPath(navigationGoal, now)) {
                    prepareNavigationPath(session, defender, navigationGoal, now)
                    pathPreparationsRemaining -= 1
                }
            } else if (defender.navigation.hasGoal) {
                if (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN) {
                    defender.blockBreaker.stop(instance)
                }
                defender.navigation.clearTerrainRecovery()
                defender.navigation.cancel()
                defender.navigation.retainCurrentChunk()
                reconcileSessionChunkLeases(session)
            }

            val reloadWasPending = defender.weapon.reloadPending
            defender.weapon.updateReload(now)
            if (reloadWasPending || targetPlayer == null || !decision.wantsToFire) continue
            if (!defender.weapon.canFire(now)) continue
            val gun = defender.weapon.gun
            val targetsInRange = targets.filter { entity.getDistanceSquared(it) <= gun.maxRange * gun.maxRange }
            if (targetPlayer !in targetsInRange) continue
            val targetPosition = Vehicle.protectedVehicleAimPosition(targetPlayer)
                ?: targetPlayer.position.add(0.0, targetPlayer.eyeHeight, 0.0)
            if (!hasClearShot(entity, targetPosition, instance)) continue
            if (!playerLikeAimAligned(entity.position, targetPosition, 12F, 10F)) continue

            entity.lookAt(targetPosition)
            gun.fireFromEntity(entity, targetPosition, targetsInRange)
            defender.weapon.recordShot(now)
        }
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
        reconcileSessionChunkLeases(session)
    }

    private fun recoverFlagRouteTerrain(
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

    private fun isAttackActive(attack: Attack): Boolean = FlagWar.chunkToAttacker[attack.coord] === attack &&
        FlagWar.blockToAttacker[attack.flagBlock] === attack

    private fun isDefenseSessionActive(session: DefenseSession): Boolean = defenseSessions[session.targetTown.uuid] === session

    private fun pruneInvalidCampaignState(session: DefenseSession) {
        val targetTown = session.targetTown
        val targetValid = Nodes.towns[targetTown.name] === targetTown && targetTown.isAi
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
        return Nodes.towns[attackingTown.name] === attackingTown &&
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

    private fun closestActiveAttack(
        session: DefenseSession,
        position: Pos,
    ): Attack? = session.attacks
        .asSequence()
        .filter(::isAttackActive)
        .minByOrNull { attack -> position.distanceSquared(attack.flagBlock.add(0.5, 0.5, 0.5)) }

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

    private fun createDefender(
        session: DefenseSession,
        slot: AiDefenderSlot,
        guardPosition: Pos,
    ): AiDefender {
        val entity = EntityCreature(EntityType.MANNEQUIN)
        val defenderName = Component.text("${session.targetTown.name} Defender", NamedTextColor.RED)
        entity.setTag(EntityTags.TRANSIENT_ENTITY, true)
        entity.setTag(EntityTags.DAMAGEABLE_MANNEQUIN, true)
        entity.getAttribute(Attribute.MAX_HEALTH).baseValue = 20.0
        entity.getAttribute(Attribute.MOVEMENT_SPEED).baseValue = 0.28
        entity.health = 20F
        entity.set(DataComponents.CUSTOM_NAME, defenderName)
        entity.isCustomNameVisible = true
        entity.editEntityMeta(MannequinMeta::class.java) { meta ->
            meta.profile = AI_DEFENDER_PROFILE
            meta.description = null
            meta.isImmovable = false
        }
        val motor = PlayerLikeGroundFollower(entity)
        entity.navigator.setNodeFollower { motor }
        entity.setEquipment(EquipmentSlot.MAIN_HAND, slot.gun.toItemStack())
        val weapon = DefenderWeapon(slot.gun)
        return AiDefender(
            entity = entity,
            brain = DefenderBrain(),
            motor = motor,
            navigation = DefenderNavigator(entity),
            weapon = weapon,
            blockBreaker = DefenderBlockBreaker(entity, weapon, diamondPickaxe),
            guardPosition = guardPosition,
        )
    }

    private fun retireDeadDefenders(
        session: DefenseSession,
        now: Long,
    ) {
        var changed = false
        session.slots.forEach { slot ->
            val defender = slot.defender ?: return@forEach
            if (!defender.entity.isDead && !defender.entity.isRemoved) return@forEach

            defender.blockBreaker.stop(session.instance)
            defender.navigation.cancel()
            defender.navigation.clearChunks()
            if (!defender.entity.isRemoved) defender.entity.remove()
            slot.defender = null
            slot.spawnPending = false
            slot.generation += 1
            val delay = runCatching(session.respawnDelayMillis)
                .getOrDefault(MAX_DEFENDER_RESPAWN_DELAY_MILLIS)
                .coerceIn(0, MAX_DEFENDER_RESPAWN_DELAY_MILLIS)
            slot.respawnAtMillis = now + delay
            changed = true
        }
        if (changed) reconcileSessionChunkLeases(session)
    }

    private fun prepareDueDefenderSpawns(
        session: DefenseSession,
        now: Long,
    ) {
        if (!isDefenseSessionActive(session) || session.spawnPreparationInFlight) return
        val wave = session.slots
            .filter { slot -> slot.defender == null && !slot.spawnPending && slot.respawnAtMillis <= now }
            .take(MAX_DEFENDER_SPAWNS_PER_WAVE)
            .map { slot ->
                slot.spawnPending = true
                slot.generation += 1
                slot to slot.generation
            }
        if (wave.isEmpty()) return
        prepareLocalDefenderSpawns(session, wave)
    }

    private fun prepareLocalDefenderSpawns(
        session: DefenseSession,
        wave: List<Pair<AiDefenderSlot, Int>>,
    ) {
        val instance = session.instance
        val targetTown = session.targetTown
        session.spawnPreparationInFlight = true
        val candidates = localDefenderSpawnColumns(targetTown, session.spawnCenter)
            .filter(instance.worldBorder::inBounds)
        if (candidates.isEmpty()) {
            failSpawnPreparationWave(session, wave, "has no blocks available near its campaign base")
            return
        }
        val guardCandidates = coreTerritoryGuardColumns(
            targetTown = targetTown,
            referenceY = session.spawnCenter.y,
            maximumColumns = maxOf(MIN_RANDOM_GUARD_COLUMNS, wave.size * RANDOM_GUARD_COLUMNS_PER_DEFENDER),
        ).filter(instance.worldBorder::inBounds)
        if (guardCandidates.isEmpty()) {
            failSpawnPreparationWave(session, wave, "has no blocks available in its core territory")
            return
        }

        val candidateChunks = buildSet {
            candidates.mapTo(this) { Coord(it.chunkX(), it.chunkZ()) }
            guardCandidates.mapTo(this) { Coord(it.chunkX(), it.chunkZ()) }
            add(Coord(session.spawnCenter.chunkX(), session.spawnCenter.chunkZ()))
        }
        session.spawnPreparationChunks.addAll(candidateChunks)
        chunkLeaseManager.loadChunks(instance, session, candidateChunks).whenComplete { _, loadError ->
            ModuleScheduler.scheduleNextTick {
                if (!isDefenseSessionActive(session)) return@scheduleNextTick
                session.spawnPreparationInFlight = false
                session.spawnPreparationChunks.clear()
                if (loadError != null) {
                    failSpawnPreparationWave(
                        session,
                        wave,
                        "could not load campaign defender terrain: ${loadError.message}",
                    )
                    return@scheduleNextTick
                }

                val safeSpawns = candidates
                    .mapNotNull { horizontalPosition ->
                        if (!instance.worldBorder.inBounds(horizontalPosition)) return@mapNotNull null
                        if (Territory.fromBlock(horizontalPosition.blockX(), horizontalPosition.blockZ())?.town !== targetTown) {
                            return@mapNotNull null
                        }
                        resolveSafeSurfaceNear(instance, horizontalPosition, session.spawnCenter.blockY())
                    }.distinctBy { spawn -> Triple(spawn.blockX(), spawn.blockY(), spawn.blockZ()) }
                    .filter { spawn ->
                        activeDefenders(session).none { defender ->
                            !defender.entity.isRemoved && defender.entity.position.distanceSquared(spawn) < 4.0
                        }
                    }
                if (safeSpawns.isEmpty()) {
                    failSpawnPreparationWave(session, wave, "has no safe defender spawns near its campaign base")
                    return@scheduleNextTick
                }
                val safeGuardPositions = guardCandidates
                    .mapNotNull { horizontalPosition ->
                        val territory = Territory.fromBlock(horizontalPosition.blockX(), horizontalPosition.blockZ())
                        if (territory?.id != targetTown.home || territory.town !== targetTown) return@mapNotNull null
                        resolveSafeGuardSurface(instance, horizontalPosition, session.spawnCenter.blockY())
                    }.distinctBy { position -> Triple(position.blockX(), position.blockY(), position.blockZ()) }
                    .filter { position ->
                        activeDefenders(session).none { defender ->
                            defender.guardPosition.distanceSquared(position) < 4.0
                        }
                    }
                if (safeGuardPositions.isEmpty()) {
                    failSpawnPreparationWave(session, wave, "has no safe guard positions in its core territory")
                    return@scheduleNextTick
                }

                val assignmentCount = minOf(wave.size, safeSpawns.size, safeGuardPositions.size)
                val assignedWave = (0 until assignmentCount).map { index ->
                    Triple(wave[index], safeSpawns[index], safeGuardPositions[index])
                }
                val retryAt = System.currentTimeMillis() + PATH_RETRY_MILLIS
                wave.drop(assignedWave.size).forEach { (slot, generation) ->
                    if (slot.generation == generation && slot.defender == null) {
                        slot.spawnPending = false
                        slot.respawnAtMillis = retryAt
                    }
                }
                assignedWave.forEach { (slotWithGeneration, spawn, guardPosition) ->
                    val (slot, generation) = slotWithGeneration
                    if (slot.generation != generation || !slot.spawnPending || slot.defender != null) {
                        return@forEach
                    }
                    slot.spawnPending = false
                    val defender = createDefender(session, slot, guardPosition)
                    slot.defender = defender
                    defender.navigation.retainChunkAt(spawn)
                    prepareDefenderSpawn(instance, session, slot, generation, defender, spawn)
                }
                reconcileSessionChunkLeases(session)
            }
        }
    }

    private fun failSpawnPreparationWave(
        session: DefenseSession,
        wave: List<Pair<AiDefenderSlot, Int>>,
        reason: String,
    ) {
        if (!isDefenseSessionActive(session)) return
        session.spawnPreparationInFlight = false
        session.spawnPreparationChunks.clear()
        System.err.println("[Nodes] AI town ${session.targetTown.name} $reason; retrying defender spawn")
        val retryAt = System.currentTimeMillis() + PATH_RETRY_MILLIS
        wave.forEach { (slot, generation) ->
            if (slot.generation == generation && slot.defender == null) {
                slot.spawnPending = false
                slot.respawnAtMillis = retryAt
            }
        }
        reconcileSessionChunkLeases(session)
    }

    internal fun localDefenderSpawnColumns(
        targetTown: Town,
        center: Pos,
        random: Random = Random.Default,
    ): List<Pos> {
        val minimumSquared = MIN_LOCAL_SPAWN_RADIUS * MIN_LOCAL_SPAWN_RADIUS
        val maximumSquared = MAX_LOCAL_SPAWN_RADIUS * MAX_LOCAL_SPAWN_RADIUS
        return buildList {
            for (offsetX in -MAX_LOCAL_SPAWN_RADIUS..MAX_LOCAL_SPAWN_RADIUS) {
                for (offsetZ in -MAX_LOCAL_SPAWN_RADIUS..MAX_LOCAL_SPAWN_RADIUS) {
                    val distanceSquared = offsetX * offsetX + offsetZ * offsetZ
                    if (distanceSquared !in minimumSquared..maximumSquared) continue
                    val x = center.blockX() + offsetX
                    val z = center.blockZ() + offsetZ
                    if (Territory.fromBlock(x, z)?.town !== targetTown) continue
                    add(Pos(x + 0.5, center.y, z + 0.5))
                }
            }
        }.shuffled(random).take(MAX_LOCAL_SPAWN_COLUMNS)
    }

    internal fun coreTerritoryGuardColumns(
        targetTown: Town,
        referenceY: Double,
        maximumColumns: Int,
        random: Random = Random.Default,
    ): List<Pos> {
        val territory = Territory.fromId(targetTown.home) ?: return emptyList()
        if (territory.town !== targetTown) return emptyList()
        val chunks = buildSet {
            add(territory.core)
            addAll(territory.chunks)
        }
        return randomTerritoryColumns(chunks, referenceY, maximumColumns, random)
            .filter { position ->
                Territory.fromBlock(position.blockX(), position.blockZ()) === territory
            }
    }

    internal fun randomTerritoryColumns(
        chunks: Collection<Coord>,
        referenceY: Double,
        maximumColumns: Int,
        random: Random = Random.Default,
    ): List<Pos> {
        require(referenceY.isFinite()) { "Guard reference height must be finite" }
        require(maximumColumns >= 0) { "Maximum guard columns cannot be negative" }
        if (maximumColumns == 0) return emptyList()
        return buildList {
            chunks.distinct().forEach { chunk ->
                for (offsetX in 0..<16) {
                    for (offsetZ in 0..<16) {
                        add(Pos(chunk.x * 16 + offsetX + 0.5, referenceY, chunk.z * 16 + offsetZ + 0.5))
                    }
                }
            }
        }.shuffled(random).take(maximumColumns)
    }

    private fun resolveSafeSurfaceNear(
        instance: Instance,
        horizontalPosition: Pos,
        referenceY: Int,
    ): Pos? {
        val dimension = instance.cachedDimensionType
        val maximumY = (referenceY + LOCAL_SPAWN_VERTICAL_SEARCH).coerceAtMost(dimension.maxY() - 2)
        val minimumY = (referenceY - LOCAL_SPAWN_VERTICAL_SEARCH).coerceAtLeast(dimension.minY() + 1)
        for (y in maximumY downTo minimumY) {
            val candidate = horizontalPosition.withY(y.toDouble())
            if (isSafeDefenderStandingPosition(instance, candidate)) return candidate
        }
        return null
    }

    private fun resolveSafeGuardSurface(
        instance: Instance,
        horizontalPosition: Pos,
        referenceY: Int,
    ): Pos? {
        resolveSafeSurfaceNear(instance, horizontalPosition, referenceY)?.let { return it }
        val dimension = instance.cachedDimensionType
        val maximumY = dimension.maxY() - 2
        val minimumY = dimension.minY() + 1
        var offset = LOCAL_SPAWN_VERTICAL_SEARCH + 1
        while (referenceY + offset <= maximumY || referenceY - offset >= minimumY) {
            val above = referenceY + offset
            if (above <= maximumY) {
                val candidate = horizontalPosition.withY(above.toDouble())
                if (isSafeDefenderStandingPosition(instance, candidate)) return candidate
            }
            val below = referenceY - offset
            if (below >= minimumY) {
                val candidate = horizontalPosition.withY(below.toDouble())
                if (isSafeDefenderStandingPosition(instance, candidate)) return candidate
            }
            offset += 1
        }
        return null
    }

    private fun navigationRouteChunks(
        start: Pos,
        target: Pos,
    ): Set<Coord> {
        val centerLineChunks = linkedSetOf<Coord>()
        val distance = horizontalDistance(start, target)
        val steps = maxOf(1, ceil(distance / NAVIGATION_ROUTE_SAMPLE_DISTANCE).toInt())
        for (step in 0..steps) {
            val progress = step.toDouble() / steps
            val point = Pos(
                start.x + (target.x - start.x) * progress,
                start.y + (target.y - start.y) * progress,
                start.z + (target.z - start.z) * progress,
            )
            centerLineChunks.add(Coord(point.chunkX(), point.chunkZ()))
        }

        val corridor = linkedSetOf<Coord>()
        corridor.addAll(centerLineChunks)
        for (center in centerLineChunks) {
            for (offsetX in -1..1) {
                for (offsetZ in -1..1) {
                    if (corridor.size >= MAX_NAVIGATION_ROUTE_CHUNKS) return corridor
                    corridor.add(Coord(center.x + offsetX, center.z + offsetZ))
                }
            }
        }
        return corridor
    }

    private fun reconcileSessionChunkLeases(session: DefenseSession) {
        chunkLeaseManager.reconcile(session)
    }

    private fun horizontalDistance(first: Pos, second: Pos): Double = hypot(first.x - second.x, first.z - second.z)

    private fun prepareDefenderSpawn(
        instance: Instance,
        session: DefenseSession,
        slot: AiDefenderSlot,
        generation: Int,
        defender: AiDefender,
        spawn: Pos,
    ) {
        val spawnChunk = Coord(spawn.chunkX(), spawn.chunkZ())
        chunkLeaseManager.loadChunks(instance, session, listOf(spawnChunk)).whenComplete { _, loadError ->
            ModuleScheduler.scheduleNextTick {
                if (loadError != null) {
                    failDefenderSpawn(session, slot, generation, defender, "failed to load campaign spawn chunk: ${loadError.message}")
                    return@scheduleNextTick
                }
                if (!isCurrentDefenderLife(session, slot, generation, defender)) {
                    if (!defender.entity.isRemoved && defender.entity.instance != null) defender.entity.remove()
                    return@scheduleNextTick
                }

                if (
                    Territory.fromBlock(spawn.blockX(), spawn.blockZ())?.town !== session.targetTown ||
                    !isSafeDefenderStandingPosition(instance, spawn)
                ) {
                    failDefenderSpawn(session, slot, generation, defender, "campaign spawn became unsafe at $spawn")
                    return@scheduleNextTick
                }

                val spawnFuture = defender.entity.setInstance(instance, spawn)
                if (spawnFuture == null) {
                    failDefenderSpawn(session, slot, generation, defender, "campaign spawn could not start at $spawn")
                    return@scheduleNextTick
                }
                spawnFuture.whenComplete { _, spawnError ->
                    ModuleScheduler.scheduleNextTick {
                        if (spawnError != null) {
                            failDefenderSpawn(session, slot, generation, defender, "campaign spawn failed: ${spawnError.message}")
                        } else if (isCurrentDefenderLife(session, slot, generation, defender)) {
                            defender.ready = true
                        } else if (!defender.entity.isRemoved) {
                            defender.entity.remove()
                        }
                    }
                }
            }
        }
    }

    private fun isCurrentDefenderLife(
        session: DefenseSession,
        slot: AiDefenderSlot,
        generation: Int,
        defender: AiDefender,
    ): Boolean = isDefenseSessionActive(session) && slot.generation == generation && slot.defender === defender

    private fun failDefenderSpawn(
        session: DefenseSession,
        slot: AiDefenderSlot,
        generation: Int,
        defender: AiDefender,
        reason: String,
    ) {
        if (!isCurrentDefenderLife(session, slot, generation, defender)) return
        System.err.println("[Nodes] AI defender $reason; retrying spawn")
        defender.navigation.cancel()
        defender.navigation.clearChunks()
        if (!defender.entity.isRemoved && defender.entity.instance != null) defender.entity.remove()
        slot.defender = null
        slot.spawnPending = false
        slot.generation += 1
        slot.respawnAtMillis = System.currentTimeMillis() + PATH_RETRY_MILLIS
        reconcileSessionChunkLeases(session)
    }

    private fun hasClearFlagReach(
        defender: EntityCreature,
        flagPosition: Pos,
        instance: Instance,
    ): Boolean = hasClearFlagReach(defender.position.add(0.0, defender.eyeHeight, 0.0), flagPosition, instance)

    private fun hasClearFlagReach(
        origin: Pos,
        flagPosition: Pos,
        instance: Instance,
    ): Boolean {
        val delta = Vec(flagPosition.x - origin.x, flagPosition.y - origin.y, flagPosition.z - origin.z)
        val distance = delta.length()
        val reachVector = if (distance <= 0.75) Vec.ZERO else delta.normalize().mul(distance - 0.75)
        return Ray(origin, reachVector).firstBlock(instance) == null
    }

    private fun hasClearShot(
        defender: EntityCreature,
        targetPosition: Pos,
        instance: Instance,
    ): Boolean {
        val origin = defender.position.add(0.0, defender.eyeHeight, 0.0)
        val vector = Vec(
            targetPosition.x - origin.x,
            targetPosition.y - origin.y,
            targetPosition.z - origin.z,
        )
        return Ray(origin, vector).firstBlock(instance) == null
    }

    private fun flagApproachPositions(
        instance: Instance,
        attack: Attack,
        defender: EntityCreature,
    ): List<Pos> {
        val flagPosition = attack.flagBlock.add(0.5, 0.5, 0.5).asPos()
        val candidates = flagApproachCandidates(instance, attack, defender.eyeHeight, FLAG_REACH.toDouble())
        val safe = candidates
            .filter { candidate ->
                instance.isChunkLoaded(candidate.chunkX(), candidate.chunkZ()) &&
                    isSafeDefenderStandingPosition(instance, candidate) &&
                    hasClearFlagReach(candidate.add(0.0, defender.eyeHeight, 0.0), flagPosition, instance)
            }
            .filterNot { candidate -> candidate.sameBlock(defender.position) }
            .sortedBy(defender.position::distanceSquared)
        if (safe.isNotEmpty()) return safe

        return candidates
            .filterNot { candidate -> candidate.sameBlock(defender.position) }
            .sortedBy(defender.position::distanceSquared)
    }

    private fun prepareNavigationPath(
        session: DefenseSession,
        defender: AiDefender,
        goal: NavigationGoal,
        now: Long,
    ) {
        val (routeTarget, finalSegment) = navigationSegment(defender.entity.position, goal.position)
        val routeChunks = navigationRouteChunks(defender.entity.position, routeTarget)
        val generation = defender.navigation.beginPathPreparation(goal, routeChunks, now)

        chunkLeaseManager.loadChunks(session.instance, session, routeChunks).whenComplete { _, loadError ->
            ModuleScheduler.scheduleNextTick {
                if (!isDefenseSessionActive(session)) return@scheduleNextTick
                if (activeDefenders(session).none { it === defender } || !defender.navigation.isCurrentGeneration(generation)) {
                    return@scheduleNextTick
                }
                if (loadError != null) {
                    failNavigationPath(session, defender, goal, "could not load route chunks: ${loadError.message}")
                    return@scheduleNextTick
                }

                val endpoint = if (finalSegment) {
                    goal.position
                } else {
                    resolveNavigationWaypoint(session.instance, routeTarget)
                }
                if (endpoint == null) {
                    failNavigationPath(session, defender, goal, "could not find a safe intermediate waypoint")
                    return@scheduleNextTick
                }
                defender.navigation.completePathPreparation(
                    generation = generation,
                    goal = goal,
                    endpoint = endpoint,
                    minimumDistance = navigationMinimumDistance(goal, finalSegment),
                )
            }
        }
        reconcileSessionChunkLeases(session)
    }

    private fun applyPreparedNavigationPaths(
        session: DefenseSession,
        now: Long,
    ) {
        activeDefenders(session).forEach { defender ->
            if (pathStartsRemaining <= 0) return@forEach
            if (!defender.navigation.hasPreparedPath) return@forEach

            val currentGoal = defender.lastDecision?.let { decision ->
                val attack = closestActiveAttack(session, defender.entity.position)
                val player = decision.targetId?.let { targetId ->
                    session.instance.players.firstOrNull { it.uuid == targetId }
                }
                navigationGoalFor(session, defender, attack, decision, player)
            } ?: defender.navigation.currentGoal
            val prepared = defender.navigation.takeCurrentPreparedPath(
                currentGoal = currentGoal,
                eligible = defender.ready && !defender.entity.isRemoved,
            ) ?: return@forEach

            if (!defender.navigation.startPreparedPath(prepared, now)) {
                failNavigationPath(session, defender, prepared.goal, "pathfinder rejected the route")
            }
            pathStartsRemaining -= 1
        }
    }

    private fun navigationGoalFor(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack?,
        decision: DefenderDecision,
        targetPlayer: Player?,
    ): NavigationGoal? {
        if (targetPlayer == null) {
            return when (decision.movement) {
                DefenderMovement.INVESTIGATE_LAST_SEEN -> decision.movementTarget?.let { position ->
                    NavigationGoal(decision.targetId, null, position.toPos())
                }

                DefenderMovement.RETURN_TO_OBJECTIVE -> objectiveNavigationGoal(session, defender, attack, decision)
                else -> attack?.let { objectiveNavigationGoal(session, defender, it) }
            }
        }
        return when (decision.movement) {
            DefenderMovement.APPROACH_TARGET,
            DefenderMovement.INVESTIGATE_LAST_SEEN,
            -> NavigationGoal(targetPlayer.uuid, null, decision.movementTarget?.toPos() ?: targetPlayer.position)

            DefenderMovement.RETREAT_FROM_TARGET,
            DefenderMovement.STRAFE_AROUND_TARGET,
            -> null

            DefenderMovement.RETURN_TO_OBJECTIVE -> objectiveNavigationGoal(session, defender, attack, decision)
            DefenderMovement.HOLD -> null
        }
    }

    private fun defenderDecision(
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

    private fun combatMovementPosition(
        defenderPosition: Pos,
        targetPosition: Pos,
        decision: DefenderDecision,
    ): Pos {
        val deltaX = defenderPosition.x - targetPosition.x
        val deltaZ = defenderPosition.z - targetPosition.z
        val length = hypot(deltaX, deltaZ).coerceAtLeast(0.001)
        val awayX = deltaX / length
        val awayZ = deltaZ / length
        return when (decision.movement) {
            DefenderMovement.RETREAT_FROM_TARGET -> defenderPosition.add(awayX * 6.0, 0.0, awayZ * 6.0)
            DefenderMovement.STRAFE_AROUND_TARGET -> {
                val side = decision.strafeDirection.toDouble()
                defenderPosition.add(-awayZ * side * 4.0, 0.0, awayX * side * 4.0)
            }
            else -> decision.movementTarget?.toPos() ?: targetPosition
        }
    }

    private fun safeDirectCombatMovementTarget(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack?,
        targetPosition: Pos,
        decision: DefenderDecision,
    ): Pos? {
        if (!defender.entity.isOnGround) return null
        val objective = campaignObjective(defender, attack)
        val preferred = combatMovementPosition(defender.entity.position, targetPosition, decision)
        val alternate = if (decision.movement == DefenderMovement.STRAFE_AROUND_TARGET) {
            combatMovementPosition(
                defender.entity.position,
                targetPosition,
                decision.copy(strafeDirection = -decision.strafeDirection),
            )
        } else {
            null
        }
        return chooseSafeDirectMovementTarget(
            position = defender.entity.position,
            preferredTarget = preferred,
            alternateTarget = alternate,
            speed = 0.28,
        ) { nextPosition ->
            session.instance.worldBorder.inBounds(nextPosition) &&
                session.instance.isChunkLoaded(nextPosition.chunkX(), nextPosition.chunkZ()) &&
                Territory.fromBlock(nextPosition.blockX(), nextPosition.blockZ())?.town === session.targetTown &&
                defender.brain.isWithinObjectiveLeash(
                    objective.toDefenderPoint(),
                    nextPosition.toDefenderPoint(),
                ) &&
                isSafeDefenderStandingPosition(session.instance, nextPosition)
        }
    }

    private fun DefenderPoint.toPos(): Pos = Pos(x, y, z)

    private fun Pos.toDefenderPoint(): DefenderPoint = DefenderPoint(x, y, z)

    private fun objectiveNavigationGoal(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
    ): NavigationGoal? {
        val approaches = flagApproachPositions(session.instance, attack, defender.entity)
        if (approaches.isEmpty()) return null
        return NavigationGoal(null, attack, defender.navigation.approachFor(approaches))
    }

    private fun objectiveNavigationGoal(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack?,
        decision: DefenderDecision,
    ): NavigationGoal? = if (attack != null) {
        objectiveNavigationGoal(session, defender, attack)
    } else {
        NavigationGoal(
            playerId = null,
            attack = null,
            position = decision.movementTarget?.toPos() ?: defender.guardPosition,
        )
    }

    private fun campaignObjective(
        defender: AiDefender,
        attack: Attack?,
    ): Pos = attack?.flagBlock?.add(0.5, 0.5, 0.5)?.asPos() ?: defender.guardPosition

    private fun failNavigationPath(
        session: DefenseSession,
        defender: AiDefender,
        goal: NavigationGoal,
        reason: String,
    ) {
        val firstFailure = defender.navigation.failPath(goal)
        reconcileSessionChunkLeases(session)
        if (firstFailure) {
            val destination = when {
                goal.playerId != null -> "player ${goal.playerId}"
                goal.attack != null -> "the colonization flag"
                else -> "the town guard position"
            }
            System.err.println("[Nodes] AI defender could not walk toward $destination: $reason")
        }
    }

    private fun navigationSegment(
        start: Pos,
        goal: Pos,
    ): Pair<Pos, Boolean> {
        val distance = start.distance(goal)
        if (distance <= NAVIGATION_SEGMENT_DISTANCE) return goal to true
        val progress = NAVIGATION_SEGMENT_DISTANCE / distance
        return Pos(
            start.x + (goal.x - start.x) * progress,
            start.y + (goal.y - start.y) * progress,
            start.z + (goal.z - start.z) * progress,
        ) to false
    }

    private fun resolveNavigationWaypoint(instance: Instance, approximate: Pos): Pos? {
        val offsets = intArrayOf(0, 4, -4, 8, -8)
        for (offsetX in offsets) {
            for (offsetZ in offsets) {
                val candidate = approximate.add(offsetX.toDouble(), 0.0, offsetZ.toDouble())
                if (!instance.worldBorder.inBounds(candidate) || !instance.isChunkLoaded(candidate)) continue
                resolveSafeSurfaceNear(instance, candidate, approximate.blockY())?.let { return it }
            }
        }
        return null
    }
}

internal fun randomDefenderRespawnDelayMillis(random: Random = Random.Default): Long = random.nextLong(
    MAX_DEFENDER_RESPAWN_DELAY_MILLIS + 1,
)
