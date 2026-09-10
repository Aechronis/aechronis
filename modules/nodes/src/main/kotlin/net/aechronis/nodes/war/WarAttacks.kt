package net.aechronis.nodes.war

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.colonization.Colonization
import net.aechronis.nodes.constants.ErrorAlreadyCaptured
import net.aechronis.nodes.constants.ErrorAlreadyUnderAttack
import net.aechronis.nodes.constants.ErrorAnnexDisabled
import net.aechronis.nodes.constants.ErrorChunkNotEdge
import net.aechronis.nodes.constants.ErrorFlagTooHigh
import net.aechronis.nodes.constants.ErrorNotBorderTerritory
import net.aechronis.nodes.constants.ErrorNotEnemy
import net.aechronis.nodes.constants.ErrorSkyBlocked
import net.aechronis.nodes.constants.ErrorTooManyAttacks
import net.aechronis.nodes.constants.ErrorTownBlacklisted
import net.aechronis.nodes.constants.ErrorTownNotWhitelisted
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.Town
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.adventure.audience.Audiences
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.block.Block
import net.minestom.server.timer.TaskSchedule
import java.util.UUID

/** Owns active attack creation, registration, ticking, and once-only cleanup. */
internal class WarAttacks(
    private val state: FlagWarState,
    private val rules: WarAttackRules,
    private val occupations: WarOccupations,
    private val presentation: WarPresentation,
    private val capture: WarCapture,
) {
    fun beginAttack(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: BlockVec,
        mode: AttackMode,
    ): Result<Attack> {
        val flagBaseX = flagBase.blockX
        val flagBaseY = flagBase.blockY
        val flagBaseZ = flagBase.blockZ
        val territory = chunk.territory
        val territoryTown = territory.town

        // run checks that chunk attack is valid

        // check chunk has a town
        if (territoryTown === null) {
            return Result.failure(ErrorNotEnemy)
        }

        if (mode == AttackMode.COLONIZATION) {
            if (!Colonization.isAuthorized(attacker, attackingTown, territoryTown)) {
                return Result.failure(ErrorNotEnemy)
            }
        } else if (mode == AttackMode.WARZONE) {
            if (!Warzone.isActive(territory) || attackingTown.nation == null) return Result.failure(ErrorNotEnemy)
        } else {
            // check if town blacklisted
            if (Nodes.config.warUseBlacklist && Nodes.config.warBlacklist.contains(territoryTown.uuid)) {
                return Result.failure(ErrorTownBlacklisted)
            }

            // check if town not whitelisted
            if (Nodes.config.warUseWhitelist) {
                if (!Nodes.config.warWhitelist.contains(territoryTown.uuid) || (Nodes.config.onlyWhitelistCanClaim && !Nodes.config.warWhitelist.contains(attackingTown.uuid))) {
                    return Result.failure(ErrorTownNotWhitelisted)
                }
            }
        }

        val pendingSkirmishTarget = if (mode == AttackMode.WAR) {
            rules.prepareSkirmishTargetSelection(attacker, attackingTown, territory)
                .getOrElse { return Result.failure(it) }
        } else {
            null
        }

        // check chunk not currently under attack
        // A warzone uses normal chunk-by-chunk war progress. The core chunk
        // is the only chunk that can capture the whole territory.
        if (chunk.attacker !== null) {
            return Result.failure(ErrorAlreadyUnderAttack)
        }

        val alreadyCaptured = when (mode) {
            AttackMode.COLONIZATION -> rules.chunkAlreadyColonizedBy(chunk, territory, attackingTown)
            AttackMode.WARZONE,
            AttackMode.WAR,
            -> rules.chunkAlreadyCaptured(chunk, territory, attackingTown)
        }
        if (alreadyCaptured) {
            return Result.failure(ErrorAlreadyCaptured)
        }

        // check chunk either:
        // 1. belongs to enemy
        // 2. town chunk occupied by enemy
        // 3. allied chunk occupied by enemy
        if (mode == AttackMode.COLONIZATION || mode == AttackMode.WARZONE || rules.chunkIsAttackable(chunk, territory, attackingTown)) {
            if (mode == AttackMode.WAR) {
                if (!rules.canCaptureTerritoryCore() && chunk.coord == territory.core) {
                    return Result.failure(ErrorAnnexDisabled)
                }

                // check for only attacking border territories
                if (state.canOnlyAttackBorders && !rules.isBorderTerritory(territory)) {
                    return Result.failure(ErrorNotBorderTerritory)
                }
            }

            // check that chunk valid, either:
            // 1. next to wilderness
            // 2. next to occupied chunk (by town or allies)
            if (!rules.chunkIsAtEdge(chunk, attackingTown)) {
                return Result.failure(ErrorChunkNotEdge)
            }

            // check that there is room to create flag
            if (flagBaseY >= 253) { // need room for wool + torch
                return Result.failure(ErrorFlagTooHigh)
            }

            // check flag has vision to sky
            val instance = MinecraftServer.getInstanceManager().instances.first()
            for (y in flagBaseY + 1..255) {
                if (!instance.getBlock(flagBaseX, y, flagBaseZ).isAir) {
                    return Result.failure(ErrorSkyBlocked)
                }
            }

            // attacker's current attacks (if any exist)
            var currentAttacks = state.attackers.get(attacker)
            if (currentAttacks == null) {
                currentAttacks = ArrayList(Nodes.config.maxPlayerChunkAttacks) // set initial capacity = max attacks
                state.attackers.put(attacker, currentAttacks)
            } else if (currentAttacks.size >= Nodes.config.maxPlayerChunkAttacks) {
                return Result.failure(ErrorTooManyAttacks)
            }

            val attack = createAttack(
                attacker,
                attackingTown,
                chunk,
                flagBase,
                mode = mode,
            )

            pendingSkirmishTarget?.takeIf(rules::commitSkirmishTargetSelection)?.let { selection ->
                attackingTown.nation?.let { nation ->
                    presentation.skirmishTargetSelected(nation, territory)
                }
            }

            // mark that save required
            state.needsSave = true

            return Result.success(attack)
        } else {
            return Result.failure(ErrorNotEnemy)
        }
    }

    fun createAttack(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: BlockVec,
        skyBeaconColorBlocksInput: MutableList<BlockVec>? = null,
        skyBeaconWireframeBlocksInput: MutableList<BlockVec>? = null,
        mode: AttackMode = AttackMode.WAR,
    ): Attack {
        val flagBaseY = flagBase.blockY
        val territory = chunk.territory

        val flagBlock = flagBase.add(0, 1, 0)
        val flagTorch = flagBase.add(0, 2, 0)
        val progressBar = presentation.createProgressBar(mode, territory, flagBase)
        val attackTime = rules.attackTime(attackingTown, territory)

        val progress = 0L

        // get sky beacon blocks
        val skyBeaconColorBlocks: MutableList<BlockVec> = if (skyBeaconColorBlocksInput === null) {
            mutableListOf()
        } else {
            skyBeaconColorBlocksInput
        }
        val skyBeaconWireframeBlocks: MutableList<BlockVec> = if (skyBeaconWireframeBlocksInput === null) {
            mutableListOf()
        } else {
            skyBeaconWireframeBlocksInput
        }

        if (skyBeaconColorBlocksInput === null || skyBeaconWireframeBlocksInput === null) {
            presentation.createAttackBeacon(
                skyBeaconColorBlocks,
                skyBeaconWireframeBlocks,
                chunk.coord,
                flagBaseY,
            )
        }

        val instance = MinecraftServer.getInstanceManager().instances.first()

        // no flag base block, set to default
        if (!Nodes.config.flagBlocks.contains(instance.getBlock(flagBase))) {
            instance.setBlock(flagBase, Nodes.config.flagBlockDefault)
        }

        // initialize flag blocks
        instance.setBlock(flagBlock, Block.DEEPSLATE)
        instance.setBlock(flagTorch, Block.TORCH)

        // create new attack instance
        val attack = Attack(
            attacker,
            attackingTown,
            chunk.coord,
            territory,
            flagBase,
            flagBlock,
            flagTorch,
            skyBeaconColorBlocks.toList(),
            skyBeaconWireframeBlocks.toList(),
            progressBar,
            attackTime,
            progress,
            mode,
        )

        // mark territory chunk under attack
        chunk.attacker = attackingTown

        // enable boss bar for player
        val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(attacker)
        if (player != null) {
            attack.progressBar.addViewer(player)
        }

        // add attack to list of attacks by attacker
        var currentAttacks = state.attackers.get(attacker)
        if (currentAttacks == null) {
            currentAttacks = ArrayList(Nodes.config.maxPlayerChunkAttacks) // set initial capacity = max attacks
            state.attackers.put(attacker, currentAttacks)
        }
        currentAttacks.add(attack)

        // map chunk to the attack
        state.chunkToAttacker.put(chunk.coord, attack)
        startAttackTask()
        presentation.requestMinimapRefresh()

        // map flag block to attack (for breaking)
        state.blockToAttacker.put(flagBlock, attack)

        if (mode == AttackMode.COLONIZATION) occupations.startSaveTask()
        notifyColonizationAttackStarted(attack)

        return attack
    }

    fun loadAttack(attacker: UUID, coord: Coord, flagBase: BlockVec, completionTime: Long) {
        val resident = Resident.fromUuid(attacker) ?: return
        val attackingTown = resident.town ?: return
        val chunk = TerritoryChunk.fromCoord(coord) ?: return
        if (chunk.attacker !== null || chunk.territory.town === null) return
        if (!rules.canCaptureTerritoryCore() && chunk.coord == chunk.territory.core) return

        val attack = createAttack(attacker, attackingTown, chunk, flagBase, mode = AttackMode.WAR)
        attack.restoreCompletionTime(completionTime)
        attack.progressBar.progress(attack.progress.toFloat() / attack.attackTime.toFloat())
        attack.textDisplay.updateProgress()

        if (attack.progress >= attack.attackTime) {
            finishAttack(attack)
        }
    }

    fun cancelAttack(attack: Attack) {
        if (!attack.markEnded()) return
        try {
            synchronized(Nodes.occupationPersistenceLock) {
                cancelAttackOnce(attack)
            }
        } finally {
            notifyColonizationAttackEnded(attack)
        }
    }

    fun cancelWarzoneAttacks(territory: Territory) {
        state.chunkToAttacker.values
            .filter { it.mode == AttackMode.WARZONE && it.targetTerritory === territory }
            .toList()
            .forEach(::cancelAttack)
    }

    private fun cancelAttackOnce(attack: Attack) {
        // remove status from territory chunk
        val chunk = TerritoryChunk.fromCoord(attack.coord)
        chunk?.attacker = null

        // remove progress bar from player
        attack.progressBar.removeViewer(Audiences.all())

        presentation.clearAttackBlocks(attack)

        // remove text display
        attack.textDisplay.remove()

        removeAttackReferences(attack)
        presentation.requestMinimapRefresh()

        // mark save needed
        state.needsSave = true
    }

    fun finishAttack(attack: Attack) {
        if (attack.mode == AttackMode.WAR && !rules.warAttackRemainsAuthorized(attack)) {
            cancelAttack(attack)
            return
        }
        if (!attack.markEnded()) return
        try {
            synchronized(Nodes.occupationPersistenceLock) {
                attack.progressBar.removeViewer(Audiences.all())
                presentation.clearAttackBlocks(attack)
                attack.textDisplay.remove()
                removeAttackReferences(attack)
                state.needsSave = true
                capture.capture(attack)
            }
        } finally {
            notifyColonizationAttackEnded(attack)
        }
    }

    fun attackTick(attack: Attack) {
        if (attack.mode == AttackMode.WAR && !rules.warAttackRemainsAuthorized(attack)) {
            attack.cancel()
            return
        }
        if (attack.mode == AttackMode.COLONIZATION && !Colonization.attackRemainsAuthorized(attack)) {
            attack.cancel()
            return
        }
        val progress = attack.updateProgressFromClock()

        if (progress >= attack.attackTime) {
            finishAttack(attack)
            return
        }

        attack.progressBar.progress(progress.toFloat() / attack.attackTime.toFloat())
        // At most five relationship-group entities are updated, never every player.
        attack.textDisplay.updateProgress()
    }

    fun revalidateWarAttacks() {
        presentation.deferMinimapRefresh {
            state.chunkToAttacker.values
                .filter { it.mode == AttackMode.WAR && !rules.warAttackRemainsAuthorized(it) }
                .toList()
                .forEach(::cancelAttack)
        }
    }

    private fun removeAttackReferences(attack: Attack) {
        state.attackers[attack.attacker]?.let { attacks ->
            attacks.remove(attack)
            if (attacks.isEmpty()) state.attackers.remove(attack.attacker)
        }
        state.chunkToAttacker.remove(attack.coord)
        state.blockToAttacker.remove(attack.flagBlock)
        stopAttackTaskIfIdle()
    }

    private fun notifyColonizationAttackStarted(attack: Attack) {
        if (attack.mode != AttackMode.COLONIZATION) return
        try {
            Colonization.onAttackStarted(attack)
        } catch (error: Exception) {
            System.err.println("Failed to start colonization defenders for ${attack.coord}: ${error.message}")
            attack.cancel()
        }
    }

    private fun notifyColonizationAttackEnded(attack: Attack) {
        if (attack.mode != AttackMode.COLONIZATION) return
        try {
            Colonization.onAttackEnded(attack)
        } catch (error: Exception) {
            System.err.println("Failed to stop colonization defenders for ${attack.coord}: ${error.message}")
        }
    }

    private fun startAttackTask() {
        if (state.attackTask != null) return
        state.attackTask = ModuleScheduler
            .buildTask {
                // A snapshot allows completion/cancellation to remove entries
                // without changing the collection currently being iterated.
                state.chunkToAttacker.values.toList().forEach(::attackTick)
            }
            .delay(TaskSchedule.tick(FlagWar.ATTACK_TICK))
            .repeat(TaskSchedule.tick(FlagWar.ATTACK_TICK))
            .schedule()
    }

    private fun stopAttackTaskIfIdle() {
        if (state.chunkToAttacker.isNotEmpty()) return
        state.attackTask?.cancel()
        state.attackTask = null
    }
}
