package net.aechronis.nodes.war

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Nametag
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.serdes.WarDeserializer
import net.aechronis.nodes.war.serdes.WarSerializer
import net.minestom.server.adventure.audience.Audiences
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import net.minestom.server.timer.Task
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Flag-based chunk conquest, conceptually based on
 * https://github.com/TownyAdvanced/Towny/tree/master/src/com/palmergames/bukkit/towny/war/flagwar
 * A flag starts a capture timer; taking a core occupies its territory.
 * This facade coordinates war sessions and exposes the shared state to existing consumers.
 */
object FlagWar {
    private val state = FlagWarState()
    private val presentation = WarPresentation(state)
    private val rules = WarAttackRules(state)
    private val occupations = WarOccupations(state, presentation)
    private val capture = WarCapture(state, rules, occupations, presentation)
    private val attacks = WarAttacks(state, rules, occupations, presentation, capture)
    private var cleanupSnapshotHandled = false

    internal const val ATTACK_TICK: Int = 20

    internal var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }

    internal var deathWar: Boolean
        get() = state.deathWar
        set(value) {
            state.deathWar = value
        }

    internal val isDeathWar: Boolean get() = state.isDeathWar

    internal var canAnnexTerritories: Boolean
        get() = state.canAnnexTerritories
        set(value) {
            state.canAnnexTerritories = value
        }

    internal var canOnlyAttackBorders: Boolean
        get() = state.canOnlyAttackBorders
        set(value) {
            state.canOnlyAttackBorders = value
        }

    internal var destructionEnabled: Boolean
        get() = state.destructionEnabled
        set(value) {
            state.destructionEnabled = value
        }

    var saveTaskPeriod: Int
        get() = state.saveTaskPeriod
        set(value) {
            state.saveTaskPeriod = value
        }

    internal val flagBlocks: MutableSet<Block> get() = state.flagBlocks

    internal var skyBeaconSize: Int
        get() = state.skyBeaconSize
        set(value) {
            state.skyBeaconSize = value
        }

    internal val attackers: HashMap<UUID, ArrayList<Attack>> get() = state.attackers

    internal val chunkToAttacker: ConcurrentHashMap<Coord, Attack> get() = state.chunkToAttacker

    internal val blockToAttacker: HashMap<BlockVec, Attack> get() = state.blockToAttacker

    internal val occupiedChunks: MutableSet<Coord> get() = state.occupiedChunks

    internal val colonizedChunks: MutableSet<Coord> get() = state.colonizedChunks

    internal val territoryOccupations: MutableMap<TerritoryId, TerritoryOccupationState> get() = state.territoryOccupations

    internal val skirmishTargetsByNation: MutableMap<UUID, TerritoryId> get() = state.skirmishTargetsByNation

    internal val townsDefeatedThisWar: MutableSet<UUID> get() = state.townsDefeatedThisWar

    internal var territoryOccupationJournalDirty: Boolean
        get() = state.territoryOccupationJournalDirty
        set(value) {
            state.territoryOccupationJournalDirty = value
        }

    internal var needsSave: Boolean
        get() = state.needsSave
        set(value) {
            state.needsSave = value
        }

    internal var saveTask: Task?
        get() = state.saveTask
        set(value) {
            state.saveTask = value
        }

    internal var attackTask: Task?
        get() = state.attackTask
        set(value) {
            state.attackTask = value
        }

    fun initialize(flagBlocks: Set<Block>) {
        cleanupSnapshotHandled = false
        state.flagBlocks.clear()
        state.flagBlocks.addAll(flagBlocks)
        state.skyBeaconSize = Nodes.config.flagBeaconSize.coerceIn(2, 16)
    }

    internal fun load() {
        // A reload must not leave a scheduler advancing discarded attacks.
        attackTask?.cancel()
        attackTask = null

        // clear all maps
        attackers.clear()
        chunkToAttacker.clear()
        blockToAttacker.clear()
        occupiedChunks.clear()
        colonizedChunks.clear()
        territoryOccupations.clear()
        skirmishTargetsByNation.clear()
        townsDefeatedThisWar.clear()
        territoryOccupationJournalDirty = false

        if (Files.exists(Nodes.config.pathWar)) {
            WarDeserializer.fromJson(Nodes.config.pathWar)
        }

        if (enabled) {
            presentation.warEnabled()
        } else if (colonizedChunks.isNotEmpty()) {
            occupations.startSaveTask()
        }
    }

    internal fun resetForReload() {
        saveTask?.cancel()
        saveTask = null
        attackTask?.cancel()
        attackTask = null

        chunkToAttacker.values.toList().forEach(attacks::cancelAttack)
        occupiedChunks.toList().forEach { coord ->
            TerritoryChunk.fromCoord(coord)?.let { chunk ->
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        attackers.clear()
        chunkToAttacker.clear()
        blockToAttacker.clear()
        occupiedChunks.clear()
        colonizedChunks.clear()
        territoryOccupations.clear()
        skirmishTargetsByNation.clear()
        townsDefeatedThisWar.clear()
        territoryOccupationJournalDirty = false
        enabled = false
        deathWar = false
        canAnnexTerritories = false
        canOnlyAttackBorders = false
        destructionEnabled = false
        needsSave = false
    }

    internal fun cleanup(persistState: Boolean = true) {
        // stop scheduled work
        saveTask?.cancel()
        saveTask = null
        attackTask?.cancel()
        attackTask = null

        // remove all progress bars from players
        for (attack in chunkToAttacker.values) {
            attack.progressBar.removeViewer(Audiences.all())
        }

        if (persistState && !cleanupSnapshotHandled) {
            synchronized(Nodes.occupationPersistenceLock) {
                WarSerializer.save(false)
                territoryOccupationJournalDirty = false
            }
        }
        cleanupSnapshotHandled = true

        // disable war
        enabled = false
        deathWar = false

        // iterate chunks and stop current attacks
        for (attack in chunkToAttacker.values.toList()) {
            val coord = attack.coord
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attacks.cancelAttack(attack)
        }

        // clear occupied chunks
        for (coord in occupiedChunks) {
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        // clear all maps
        attackers.clear()
        chunkToAttacker.clear()
        blockToAttacker.clear()
        occupiedChunks.clear()
        colonizedChunks.clear()
        territoryOccupations.clear()
        skirmishTargetsByNation.clear()
        townsDefeatedThisWar.clear()
        territoryOccupationJournalDirty = false
    }

    internal fun enable(
        canAnnexTerritories: Boolean,
        canOnlyAttackBorders: Boolean,
        destructionEnabled: Boolean,
        deathWar: Boolean = false,
    ) {
        skirmishTargetsByNation.clear()
        townsDefeatedThisWar.clear()
        enabled = true
        FlagWar.deathWar = deathWar
        FlagWar.canAnnexTerritories = canAnnexTerritories
        FlagWar.canOnlyAttackBorders = canOnlyAttackBorders
        FlagWar.destructionEnabled = destructionEnabled
        needsSave = true

        occupations.startSaveTask(restart = true)
        attacks.revalidateWarAttacks()
        Nametag.refreshRelationships()
        Resident.renderMinimaps()
    }

    internal fun disable() {
        enabled = false
        deathWar = false
        canAnnexTerritories = false
        canOnlyAttackBorders = false
        destructionEnabled = false
        skirmishTargetsByNation.clear()
        townsDefeatedThisWar.clear()
        needsSave = true

        // kill save task
        saveTask?.cancel()
        saveTask = null

        // stop global war flags without touching independent colonization flags
        chunkToAttacker.values.filter { it.mode == AttackMode.WAR }.toList().forEach(attacks::cancelAttack)

        // clear global war captures while preserving colonized chunks
        for (coord in occupiedChunks.toList()) {
            if (coord in colonizedChunks) continue
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            occupiedChunks.remove(coord)
        }

        attackers.entries.removeIf { it.value.isEmpty() }
        Nametag.refreshRelationships()
        Resident.renderMinimaps()

        if (colonizedChunks.isNotEmpty() || chunkToAttacker.values.any { it.mode == AttackMode.COLONIZATION }) {
            occupations.startSaveTask()
        }

        // save war.json with any retained colony progress
        try {
            synchronized(Nodes.occupationPersistenceLock) {
                if (territoryOccupationJournalDirty) {
                    occupations.flushTerritoryOccupationJournal()
                } else {
                    WarSerializer.save(false)
                }
                needsSave = false
            }
        } catch (error: Exception) {
            needsSave = true
            occupations.startSaveTask()
            throw error
        }
    }

    fun printInfo(sender: CommandSender, detailed: Boolean = false) = presentation.printInfo(sender, detailed)

    internal object SaveLoop : Runnable {
        override fun run() = occupations.save()
    }

    internal fun beginAttack(attacker: UUID, attackingTown: Town, chunk: TerritoryChunk, flagBase: BlockVec): Result<Attack> = attacks.beginAttack(attacker, attackingTown, chunk, flagBase, AttackMode.WAR)

    internal fun beginColonizationAttack(attacker: UUID, attackingTown: Town, chunk: TerritoryChunk, flagBase: BlockVec): Result<Attack> = attacks.beginAttack(attacker, attackingTown, chunk, flagBase, AttackMode.COLONIZATION)

    internal fun beginWarzoneAttack(attacker: UUID, attackingTown: Town, chunk: TerritoryChunk, flagBase: BlockVec): Result<Attack> = attacks.beginAttack(attacker, attackingTown, chunk, flagBase, AttackMode.WARZONE)

    internal fun canCaptureTerritoryCore(): Boolean = rules.canCaptureTerritoryCore()

    internal fun canAnnexDefeatedTown(mode: AttackMode): Boolean = rules.canAnnexDefeatedTown(mode)

    internal fun isBorderTerritory(territory: Territory): Boolean = rules.isBorderTerritory(territory)

    internal fun shouldAnnexTown(
        defeatedTown: Town,
        capturedTerritory: Territory,
    ): Boolean = rules.shouldAnnexTown(defeatedTown, capturedTerritory)

    internal fun chunkAlreadyCaptured(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean = rules.chunkAlreadyCaptured(chunk, territory, attackingTown)

    internal fun chunkAlreadyColonizedBy(
        chunk: TerritoryChunk,
        territory: Territory,
        attackingTown: Town,
    ): Boolean = rules.chunkAlreadyColonizedBy(chunk, territory, attackingTown)

    internal fun townIsWarOpponent(attackingTown: Town, otherTown: Town?): Boolean = rules.townIsWarOpponent(attackingTown, otherTown)

    internal fun prepareSkirmishTargetSelection(
        attacker: UUID,
        attackingTown: Town,
        territory: Territory,
    ): Result<SkirmishTargetSelection?> = rules.prepareSkirmishTargetSelection(attacker, attackingTown, territory)

    internal fun commitSkirmishTargetSelection(selection: SkirmishTargetSelection): Boolean = rules.commitSkirmishTargetSelection(selection)

    internal fun skirmishTarget(attackingTown: Town): Territory? = rules.skirmishTarget(attackingTown)

    internal fun chunkIsAttackable(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean = rules.chunkIsAttackable(chunk, territory, attackingTown)

    internal fun chunkIsAtEdge(chunk: TerritoryChunk, attackingTown: Town): Boolean = rules.chunkIsAtEdge(chunk, attackingTown)

    internal fun canAttackFromNeighborChunk(neighborChunk: TerritoryChunk?, attacker: Town): Boolean = rules.canAttackFromNeighborChunk(neighborChunk, attacker)

    internal fun loadOccupiedChunk(townId: UUID, coord: Coord) = occupations.loadOccupiedChunk(townId, coord)

    internal fun loadColonizedChunk(coord: Coord) = occupations.loadColonizedChunk(coord)

    internal fun isColonized(coord: Coord): Boolean = occupations.isColonized(coord)

    internal fun loadTerritoryOccupation(
        territoryId: TerritoryId,
        occupierId: UUID?,
        colonized: Boolean,
    ) = occupations.loadTerritoryOccupation(territoryId, occupierId, colonized)

    internal fun commitTerritoryOccupation(
        territory: Territory,
        occupier: Town?,
        colonized: Boolean,
        flushJournal: Boolean = true,
    ) = occupations.commitTerritoryOccupation(territory, occupier, colonized, flushJournal)

    internal fun flushTerritoryOccupationJournal() = occupations.flushTerritoryOccupationJournal()

    internal fun clearTerritoryOccupation(territory: Territory) = occupations.clearTerritoryOccupation(territory)

    internal fun clearOccupationsBy(town: Town) = occupations.clearOccupationsBy(town)

    internal fun stopColonizationCampaign(
        attacker: UUID,
        attackingTown: Town,
        targetTown: Town,
        abandonCompletedProgress: Boolean,
    ) = occupations.stopColonizationCampaign(attacker, attackingTown, targetTown, abandonCompletedProgress)

    internal fun loadSkirmishTarget(nationId: UUID, territoryId: TerritoryId) = occupations.loadSkirmishTarget(nationId, territoryId)

    internal fun loadDefeatedTown(townId: UUID) = occupations.loadDefeatedTown(townId)

    internal fun resolveTownDefeat(
        attackerTown: Town,
        defeatedTown: Town,
        mode: AttackMode,
    ): TownDefeatOutcome = capture.resolveTownDefeat(attackerTown, defeatedTown, mode)

    internal fun createAttack(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: BlockVec,
        skyBeaconColorBlocksInput: MutableList<BlockVec>? = null,
        skyBeaconWireframeBlocksInput: MutableList<BlockVec>? = null,
        mode: AttackMode = AttackMode.WAR,
    ): Attack = attacks.createAttack(attacker, attackingTown, chunk, flagBase, skyBeaconColorBlocksInput, skyBeaconWireframeBlocksInput, mode)

    internal fun loadAttack(attacker: UUID, coord: Coord, flagBase: BlockVec, completionTime: Long) = attacks.loadAttack(attacker, coord, flagBase, completionTime)

    internal fun cancelAttack(attack: Attack) = attacks.cancelAttack(attack)

    internal fun cancelWarzoneAttacks(territory: Territory) = attacks.cancelWarzoneAttacks(territory)

    internal fun finishAttack(attack: Attack) = attacks.finishAttack(attack)

    internal fun attackTick(attack: Attack) = attacks.attackTick(attack)

    internal fun revalidateWarAttacks() = attacks.revalidateWarAttacks()

    internal fun createAttackBeacon(
        skyBeaconColorBlocks: MutableList<BlockVec>,
        skyBeaconWireframeBlocks: MutableList<BlockVec>,
        coord: Coord,
        flagBaseY: Int,
    ) = presentation.createAttackBeacon(skyBeaconColorBlocks, skyBeaconWireframeBlocks, coord, flagBaseY)

    internal fun requestMinimapRefresh() = presentation.requestMinimapRefresh()

    internal fun deferMinimapRefresh(block: () -> Unit) = presentation.deferMinimapRefresh(block)

    fun sendWarProgressBarToPlayer(player: Player) = presentation.sendWarProgressBarToPlayer(player)

    fun refreshAttackTextDisplays() = presentation.refreshAttackTextDisplays()
}
