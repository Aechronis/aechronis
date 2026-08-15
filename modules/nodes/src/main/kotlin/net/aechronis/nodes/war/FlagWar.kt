/**
 * Flag war implementation conceptually based on
 * Towny flag war:
 * https://github.com/TownyAdvanced/Towny/tree/master/src/com/palmergames/bukkit/towny/war/flagwar
 *
 * War handled by placing a "flag" block onto a chunk to start
 * a "conquer" timer. When timer ends, the chunk is claimed
 * by the attacker's town.
 *
 * When a territory's core is taken, the territory is converted
 * to "occupied" status by the attacking town.
 *
 * Flag block object:
 *     i       <- torch for light (so players can see it)
 *    [ ]      <- wool beacon block (destroy to cancel)
 *     |       <- initial item placed to start claim
 *
 * ----
 * i dont even fully understand save architecture anymore after 6 months
 * hope this doesnt break during war time :^)
 * t. xeth
 */

package net.aechronis.nodes.war

import net.aechronis.nodes.Message
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
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.serdes.WarDeserializer
import net.aechronis.nodes.war.serdes.WarSerializer
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.adventure.audience.Audiences
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val SKY_BEACON_FRAME_BLOCK = Block.SEA_LANTERN
private val SKY_BEACON_BLOCK = Block.BLACK_WOOL

// contain all flag materials for sky beacon
private val SKY_BEACON_BLOCKS: Set<Block> = setOf(
    SKY_BEACON_FRAME_BLOCK,
    SKY_BEACON_BLOCK,
)

internal data class TerritoryOccupationState(
    val occupierId: UUID?,
    val colonized: Boolean,
)

object FlagWar {

    // ============================================
    // war settings
    // ============================================
    // flag that war is turned on
    internal var enabled: Boolean = false

    // allow annexing territories during war (disable for border skirmishes)
    internal var canAnnexTerritories: Boolean = false

    // only allow attacking border territories, cannot go deeper in
    internal var canOnlyAttackBorders: Boolean = false

    // TODO: war permissions, can create/destroy during war
    internal var destructionEnabled: Boolean = false

    // ticks for the save task
    var saveTaskPeriod: Int = 20
    // ============================================

    // flag items that can be used to claim during war
    internal val flagBlocks: MutableSet<Block> = mutableSetOf()

    // flag sky beacon size, must be in [2, 16]
    internal var skyBeaconSize: Int = 6

    // map attacker UUID -> List of Attack instances
    internal val attackers: HashMap<UUID, ArrayList<Attack>> = hashMapOf()

    // map chunk -> Attack instance
    internal val chunkToAttacker: ConcurrentHashMap<Coord, Attack> = ConcurrentHashMap()

    // map flag block -> Attack instance (for cancelling attacks)
    internal val blockToAttacker: HashMap<BlockVec, Attack> = hashMapOf()

    // set of all occupied chunks
    internal val occupiedChunks: MutableSet<Coord> = ConcurrentHashMap.newKeySet() // create concurrent set from ConcurrentHashMap

    internal val colonizedChunks: MutableSet<Coord> = ConcurrentHashMap.newKeySet()

    internal val territoryOccupations: MutableMap<TerritoryId, TerritoryOccupationState> = hashMapOf()

    internal var territoryOccupationJournalDirty: Boolean = false

    // attack/flag update tick interval
    internal const val ATTACK_TICK: Int = 20

    // flag that save required
    @Volatile
    internal var needsSave: Boolean = false

    // periodic task to check for save
    internal var saveTask: Task? = null

    fun initialize(flagBlocks: Set<Block>) {
        this.flagBlocks.clear()
        FlagWar.flagBlocks.addAll(flagBlocks)
        skyBeaconSize = Nodes.config.flagBeaconSize.coerceIn(2, 16)
    }

    /**
     * Print info to sender about current war state
     */
    fun printInfo(sender: CommandSender, detailed: Boolean = false) {
        val status = if (enabled) "enabled" else "${ChatColor.GRAY}disabled"
        Message.print(sender, "${ChatColor.BOLD}Nodes war status: $status")
        if (enabled) {
            Message.print(sender, "- Can Annex Territories${ChatColor.WHITE}: $canAnnexTerritories")
            Message.print(sender, "- Can Only Attack Borders${ChatColor.WHITE}: $canOnlyAttackBorders")
            Message.print(sender, "- Destruction Enabled${ChatColor.WHITE}: $destructionEnabled")
            if (detailed) {
                Message.print(sender, "- Using Towns Whitelist${ChatColor.WHITE}: ${Nodes.config.warUseWhitelist}")
                Message.print(sender, "- Can leave town${ChatColor.WHITE}: ${Nodes.config.canLeaveTownDuringWar}")
            }
        }
    }

    /**
     * Async save loop task
     */
    internal object SaveLoop : Runnable {
        override fun run() {
            synchronized(Nodes.occupationPersistenceLock) {
                if (!needsSave && !territoryOccupationJournalDirty) return
                try {
                    if (territoryOccupationJournalDirty) {
                        flushTerritoryOccupationJournal()
                        needsSave = false
                    } else {
                        needsSave = false
                        WarSerializer.save(true).whenComplete { _, error ->
                            if (error != null) needsSave = true
                        }
                    }
                } catch (error: Exception) {
                    needsSave = true
                    System.err.println("Failed to save war state: ${error.message}")
                }
            }
        }
    }

    /**
     * Load war state from .json file
     */
    internal fun load() {
        // clear all maps
        attackers.clear()
        chunkToAttacker.clear()
        blockToAttacker.clear()
        occupiedChunks.clear()
        colonizedChunks.clear()
        territoryOccupations.clear()
        territoryOccupationJournalDirty = false

        if (Files.exists(Nodes.config.pathWar)) {
            WarDeserializer.fromJson(Nodes.config.pathWar)
        }

        if (enabled) {
            if (canOnlyAttackBorders) {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishing enabled")
            } else {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")
            }
        } else if (colonizedChunks.isNotEmpty()) {
            startSaveTask()
        }
    }

    /**
     * Load an occupied chunk from json
     */
    internal fun loadOccupiedChunk(townName: String, coord: Coord) {
        // get town
        val town = runCatching { UUID.fromString(townName) }.getOrNull()
            ?.let(Town::fromUuid)
            ?: Town.fromName(townName)
        if (town == null) {
            return
        }

        // get territory chunk
        val terrChunk = TerritoryChunk.fromCoord(coord)
        if (terrChunk == null) {
            return
        }

        // mark chunk occupied
        terrChunk.occupier = town
        occupiedChunks.add(terrChunk.coord)
    }

    internal fun loadColonizedChunk(coord: Coord) {
        val chunk = TerritoryChunk.fromCoord(coord) ?: return
        if (chunk.occupier != null && occupiedChunks.contains(coord)) colonizedChunks.add(coord)
    }

    internal fun isColonized(coord: Coord): Boolean = colonizedChunks.contains(coord)

    internal fun loadTerritoryOccupation(
        territoryId: TerritoryId,
        occupierId: UUID?,
        colonized: Boolean,
    ) {
        territoryOccupations[territoryId] = TerritoryOccupationState(occupierId, colonized)
        val territory = Territory.fromId(territoryId) ?: return
        val occupier = occupierId?.let(Town::fromUuid)
        if (occupierId != null && occupier == null) {
            System.err.println("[Nodes] Ignoring unknown town $occupierId in territory occupation $territoryId")
            return
        }
        Town.restoreOccupation(territory, occupier)
    }

    /**
     * Records a complete territory transition before a later towns.json
     * snapshot can expose the same in-memory occupation.
     */
    internal fun commitTerritoryOccupation(
        territory: Territory,
        occupier: Town?,
        colonized: Boolean,
    ) = synchronized(Nodes.occupationPersistenceLock) {
        territoryOccupations[territory.id] = TerritoryOccupationState(occupier?.uuid, colonized)
        territoryOccupationJournalDirty = true
        flushTerritoryOccupationJournal()
    }

    /** Persist a previously failed occupation transition before towns.json. */
    internal fun flushTerritoryOccupationJournal() = synchronized(Nodes.occupationPersistenceLock) {
        if (!territoryOccupationJournalDirty) return@synchronized
        WarSerializer.save(false)
        territoryOccupationJournalDirty = false
    }

    /** Upgrade legacy colony saves using the core chunk as explicit evidence. */
    internal fun migrateLegacyTerritoryOccupations() {
        Nodes.territories.values.forEach { territory ->
            if (territory.id in territoryOccupations) return@forEach
            val core = TerritoryChunk.fromCoord(territory.core) ?: return@forEach
            if (core.coord !in colonizedChunks) return@forEach
            val occupier = core.occupier ?: return@forEach
            territoryOccupations[territory.id] = TerritoryOccupationState(occupier.uuid, colonized = true)
            Town.restoreOccupation(territory, occupier)
            territoryOccupationJournalDirty = true
            needsSave = true
        }
    }

    /**
     * Clear all live and persisted chunk-level occupation state for a territory.
     * Territory ownership/occupation itself remains the caller's responsibility.
     */
    internal fun clearTerritoryOccupation(territory: Territory) {
        territory.chunks.forEach { coord ->
            chunkToAttacker[coord]?.cancel()
            TerritoryChunk.fromCoord(coord)?.let { chunk ->
                chunk.attacker = null
                chunk.occupier = null
            }
            occupiedChunks.remove(coord)
            colonizedChunks.remove(coord)
        }
        needsSave = true
        Resident.renderMinimaps()
    }

    /** Remove partial occupations and active flags owned by a town being deleted. */
    internal fun clearOccupationsBy(town: Town) {
        chunkToAttacker.values
            .filter { attack -> attack.town === town || attack.targetTerritory.town === town }
            .toList()
            .forEach(Attack::cancel)
        occupiedChunks.toList().forEach { coord ->
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk?.occupier === town) {
                chunk.occupier = null
                occupiedChunks.remove(coord)
                colonizedChunks.remove(coord)
            }
        }
        needsSave = true
        Resident.renderMinimaps()
    }

    /**
     * Stop one player's live flags for a town-to-town colonization campaign.
     * When the last participant stops, also release every completed chunk and
     * territory that this attacking town colonized from the selected AI town.
     */
    internal fun stopColonizationCampaign(
        attacker: UUID,
        attackingTown: Town,
        targetTown: Town,
        abandonCompletedProgress: Boolean,
    ) = synchronized(Nodes.occupationPersistenceLock) {
        chunkToAttacker.values
            .filter { attack ->
                attack.mode == AttackMode.COLONIZATION &&
                    attack.town === attackingTown &&
                    attack.targetTown === targetTown &&
                    (abandonCompletedProgress || attack.attacker == attacker)
            }.toList()
            .forEach(Attack::cancel)

        if (!abandonCompletedProgress) return@synchronized

        var changed = false
        Nodes.territories.values
            .filter { territory -> territory.town === targetTown }
            .forEach { territory ->
                val occupation = territoryOccupations[territory.id]
                val territoryColonizedByAttacker = territory.occupier === attackingTown &&
                    occupation?.occupierId == attackingTown.uuid &&
                    occupation.colonized

                territory.chunks.forEach { coord ->
                    val chunk = TerritoryChunk.fromCoord(coord) ?: return@forEach
                    if (coord !in colonizedChunks || chunk.occupier !== attackingTown) return@forEach
                    chunk.occupier = null
                    occupiedChunks.remove(coord)
                    colonizedChunks.remove(coord)
                    changed = true
                }

                if (territoryColonizedByAttacker) {
                    Town.restoreOccupation(territory, null)
                    territoryOccupations[territory.id] = TerritoryOccupationState(null, colonized = false)
                    territoryOccupationJournalDirty = true
                    changed = true
                }
            }

        if (changed) {
            needsSave = true
            Resident.renderMinimaps()
            WarSerializer.save(false)
            territoryOccupationJournalDirty = false
            needsSave = false
        }
    }

    /** Cancel live tasks and discard in-memory war state before reloading world data. */
    internal fun resetForReload() {
        saveTask?.cancel()
        saveTask = null

        chunkToAttacker.values.toList().forEach { attack ->
            attack.thread.cancel()
            cancelAttack(attack)
        }
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
        territoryOccupationJournalDirty = false
        enabled = false
        canAnnexTerritories = false
        canOnlyAttackBorders = false
        destructionEnabled = false
        needsSave = false
    }

    // cleanup when server is shutdown
    internal fun cleanup() {
        // stop save task
        saveTask?.cancel()
        saveTask = null

        // remove all progress bars from players
        for (attack in chunkToAttacker.values) {
            attack.progressBar.removeViewer(Audiences.all())
        }

        synchronized(Nodes.occupationPersistenceLock) {
            WarSerializer.save(false)
            territoryOccupationJournalDirty = false
        }

        // disable war
        enabled = false

        // iterate chunks and stop current attacks
        for ((coord, attack) in chunkToAttacker) {
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attack.thread.cancel()
            cancelAttack(attack)
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
        territoryOccupationJournalDirty = false
    }

    /**
     * Enable war, set war state flags
     */
    internal fun enable(canAnnexTerritories: Boolean, canOnlyAttackBorders: Boolean, destructionEnabled: Boolean) {
        enabled = true
        FlagWar.canAnnexTerritories = canAnnexTerritories
        FlagWar.canOnlyAttackBorders = canOnlyAttackBorders
        FlagWar.destructionEnabled = destructionEnabled
        needsSave = true

        startSaveTask(restart = true)
    }

    /**
     * Disable war, cleanup war state
     */
    internal fun disable() {
        enabled = false
        canAnnexTerritories = false
        canOnlyAttackBorders = false
        destructionEnabled = false
        needsSave = true

        // kill save task
        saveTask?.cancel()
        saveTask = null

        // stop global war flags without touching independent colonization flags
        chunkToAttacker.values.filter { it.mode == AttackMode.WAR }.toList().forEach { attack ->
            attack.thread.cancel()
            cancelAttack(attack)
        }

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
        Resident.renderMinimaps()

        if (colonizedChunks.isNotEmpty() || chunkToAttacker.values.any { it.mode == AttackMode.COLONIZATION }) {
            startSaveTask()
        }

        // save war.json with any retained colony progress
        try {
            synchronized(Nodes.occupationPersistenceLock) {
                if (territoryOccupationJournalDirty) {
                    flushTerritoryOccupationJournal()
                } else {
                    WarSerializer.save(false)
                }
                needsSave = false
            }
        } catch (error: Exception) {
            needsSave = true
            startSaveTask()
            throw error
        }
    }

    // initiate attack on a territory chunk:
    // 1. check chunk is valid target, flag placement valid,
    //    and player can attack
    // 2. create and run attack timer thread
    internal fun beginAttack(attacker: UUID, attackingTown: Town, chunk: TerritoryChunk, flagBase: BlockVec): Result<Attack> = beginAttack(attacker, attackingTown, chunk, flagBase, AttackMode.WAR)

    internal fun beginColonizationAttack(attacker: UUID, attackingTown: Town, chunk: TerritoryChunk, flagBase: BlockVec): Result<Attack> = beginAttack(attacker, attackingTown, chunk, flagBase, AttackMode.COLONIZATION)

    private fun beginAttack(
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

        // check chunk not currently under attack
        if (chunk.attacker !== null) {
            return Result.failure(ErrorAlreadyUnderAttack)
        }

        // check chunk not already captured by town or allies
        val alreadyCaptured = if (mode == AttackMode.COLONIZATION) {
            chunkAlreadyColonizedBy(chunk, territory, attackingTown)
        } else {
            chunkAlreadyCaptured(chunk, territory, attackingTown)
        }
        if (alreadyCaptured) {
            return Result.failure(ErrorAlreadyCaptured)
        }

        // check chunk either:
        // 1. belongs to enemy
        // 2. town chunk occupied by enemy
        // 3. allied chunk occupied by enemy
        if (mode == AttackMode.COLONIZATION || chunkIsEnemy(chunk, territory, attackingTown)) {
            if (mode == AttackMode.WAR) {
                if (!canAnnexTerritories && chunk.coord == territory.core) {
                    return Result.failure(ErrorAnnexDisabled)
                }

                // check for only attacking border territories
                if (canOnlyAttackBorders && !isBorderTerritory(territory)) {
                    return Result.failure(ErrorNotBorderTerritory)
                }
            }

            // check that chunk valid, either:
            // 1. next to wilderness
            // 2. next to occupied chunk (by town or allies)
            if (!chunkIsAtEdge(chunk, attackingTown)) {
                return Result.failure(ErrorChunkNotEdge)
            }

            // check that there is room to create flag
            if (flagBaseY >= 253) { // need room for wool + torch
                return Result.failure(ErrorFlagTooHigh)
            }

            // check flag has vision to sky
            for (y in flagBaseY + 1..255) {
                if (!MinecraftServer.getInstanceManager().instances.first().getBlock(flagBaseX, y, flagBaseZ).isAir) {
                    return Result.failure(ErrorSkyBlocked)
                }
            }

            // attacker's current attacks (if any exist)
            var currentAttacks = attackers.get(attacker)
            if (currentAttacks == null) {
                currentAttacks = ArrayList(Nodes.config.maxPlayerChunkAttacks) // set initial capacity = max attacks
                attackers.put(attacker, currentAttacks)
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

            // mark that save required
            needsSave = true

            return Result.success(attack)
        } else {
            return Result.failure(ErrorNotEnemy)
        }
    }

    // actually creates attack instance
    internal fun createAttack(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: BlockVec,
        skyBeaconColorBlocksInput: MutableList<BlockVec>? = null,
        skyBeaconWireframeBlocksInput: MutableList<BlockVec>? = null,
        mode: AttackMode = AttackMode.WAR,
    ): Attack {
        val flagBaseX = flagBase.blockX
        val flagBaseY = flagBase.blockY
        val flagBaseZ = flagBase.blockZ
        val territory = chunk.territory

        val flagBlock = flagBase.add(0, 1, 0)
        val flagTorch = flagBase.add(0, 2, 0)
        val action = if (mode == AttackMode.COLONIZATION) "Colonizing" else "Attacking"
        val progressBar = BossBar.bossBar(Component.text("$action ${territory.town!!.name} at ($flagBaseX, $flagBaseY, $flagBaseZ)"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)

        // calculate max attack time based on chunk and other modifiers
        // convert milliseconds to ticks
        var attackTime = Nodes.config.chunkAttackTime.toDouble() * 20 / 1000
        if (territory.bordersWilderness) {
            attackTime *= Nodes.config.chunkAttackFromWastelandMultiplier
        }
        // town specific claim time modifiers
        val terrTown = territory.town
        if (terrTown !== null) {
            if (territory.id == terrTown.home) {
                attackTime *= Nodes.config.chunkAttackHomeMultiplier
            }
            attackTime *= if (terrTown.uuid == attackingTown.uuid || Town.areAllied(terrTown, attackingTown)) {
                territory.defenderTimeMultiplier
            } else {
                territory.attackerTimeMultiplier
            }
        }

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
            createAttackBeacon(
                skyBeaconColorBlocks,
                skyBeaconWireframeBlocks,
                chunk.coord,
                flagBaseY,
            )
        }

        // no flag base block, set to default
        if (!Nodes.config.flagBlocks.contains(MinecraftServer.getInstanceManager().instances.first().getBlock(flagBase))) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(flagBase, Nodes.config.flagBlockDefault)
        }

        // initialize flag blocks
        MinecraftServer.getInstanceManager().instances.first().setBlock(flagBlock, Block.DEEPSLATE)
        MinecraftServer.getInstanceManager().instances.first().setBlock(flagTorch, Block.TORCH)

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
            attackTime.toLong(),
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
        var currentAttacks = attackers.get(attacker)
        if (currentAttacks == null) {
            currentAttacks = ArrayList(Nodes.config.maxPlayerChunkAttacks) // set initial capacity = max attacks
            attackers.put(attacker, currentAttacks)
        }
        currentAttacks.add(attack)

        // map chunk to the attack
        chunkToAttacker.put(chunk.coord, attack)
        Resident.renderMinimaps()

        // map flag block to attack (for breaking)
        blockToAttacker.put(flagBlock, attack)

        if (mode == AttackMode.COLONIZATION) startSaveTask()
        notifyColonizationAttackStarted(attack)

        return attack
    }

    internal fun loadAttack(attacker: UUID, coord: Coord, flagBase: BlockVec, completionTime: Long) {
        val resident = Resident.fromUuid(attacker) ?: return
        val attackingTown = resident.town ?: return
        val chunk = TerritoryChunk.fromCoord(coord) ?: return
        if (chunk.attacker !== null || chunk.territory.town === null) return
        if (!canAnnexTerritories && chunk.coord == chunk.territory.core) return

        val attack = createAttack(attacker, attackingTown, chunk, flagBase, mode = AttackMode.WAR)
        val now = System.currentTimeMillis() / 1000
        val remainingTicks = ((completionTime - now) * ATTACK_TICK).coerceAtLeast(0)
        attack.progress = (attack.attackTime - remainingTicks).coerceIn(0, attack.attackTime)
        attack.progressBar.progress(attack.progress.toFloat() / attack.attackTime.toFloat())

        if (attack.progress >= attack.attackTime) {
            attack.thread.cancel()
            finishAttack(attack)
        }
    }

    // check if territory is a border territory of a town, requirements:
    // any adjacent territory is not of the same town
    internal fun isBorderTerritory(territory: Territory): Boolean {
        // do not allow attacking home territory
        val territoryTown = territory.town
        if (territoryTown !== null && territoryTown.home == territory.id) {
            return false
        }

        // territory borders wilderness (no territories)
        if (territory.bordersWilderness) {
            return true
        }

        // otherwise, check if any neighbor territory is not owned by the town
        for (neighborTerritoryId in territory.neighbors) {
            val neighborTerritory = Nodes.territories[neighborTerritoryId]
            if (neighborTerritory !== null && neighborTerritory.town !== territoryTown) {
                return true
            }
        }

        return false
    }

    /** A successful territory capture defeats its town by taking its home or crossing 70% occupation. */
    internal fun shouldAnnexTown(
        defeatedTown: Town,
        capturedTerritory: Territory,
    ): Boolean {
        if (capturedTerritory.town !== defeatedTown) return false
        if (capturedTerritory.id == defeatedTown.home) return true

        val totalTerritories = defeatedTown.territories.size
        if (totalTerritories == 0) return false
        val capturedTerritories = defeatedTown.territories.count { territoryId ->
            Territory.fromId(territoryId)?.occupier?.let { occupier -> occupier !== defeatedTown } == true
        }
        return capturedTerritories.toLong() * 10 > totalTerritories.toLong() * 7
    }

    // check if chunk was already captured
    // 1. territory occupied by town or allies and chunk not occupied
    // 2. chunk occupied by town or allies
    internal fun chunkAlreadyCaptured(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        val territoryOccupier = territory.occupier
        val chunkOccupier = chunk.occupier

        if (territoryOccupier === attackingTown || Town.areAllied(attackingTown, territoryOccupier)) {
            if (!Town.areEnemies(attackingTown, chunkOccupier)) {
                return true
            }
        }

        if (chunkOccupier !== null) {
            if (chunkOccupier === attackingTown || Town.areAllied(attackingTown, chunkOccupier)) {
                return true
            }
        }

        return false
    }

    internal fun chunkAlreadyColonizedBy(
        chunk: TerritoryChunk,
        territory: Territory,
        attackingTown: Town,
    ): Boolean {
        val effectiveOccupier = chunk.occupier ?: territory.occupier ?: return false
        return effectiveOccupier === attackingTown || Town.areAllied(attackingTown, effectiveOccupier)
    }

    // check chunk belongs to an enemy and can be attacked:
    // 1. belongs to enemy town
    // 2. town chunk occupied by enemy
    // 3. allied chunk occupied by enemy
    // 4. town's occupied territory, chunk occupied by enemy
    // 5. ally's occupied territory, chunk occupied by enemy
    internal fun chunkIsEnemy(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        if (Town.areEnemies(attackingTown, territory.town)) {
            return true
        }

        val attackingNation = attackingTown.nation
        val territoryNation = territory.town?.nation

        // your town, nation, or ally town chunk occupied by enemy
        if ((territory.town === attackingTown) ||
            (attackingNation !== null && attackingNation === territoryNation) ||
            (Town.areAllied(attackingTown, territory.town))
        ) {
            if (Town.areEnemies(attackingTown, territory.occupier)) {
                return true
            }
            if (Town.areEnemies(attackingTown, chunk.occupier)) {
                return true
            }
        }

        // your occupied territory or ally's occupied territory
        // chunk occupied by enemy
        val occupier = territory.occupier
        val occupierNation = occupier?.nation
        if (occupier === attackingTown ||
            (attackingNation !== null && attackingNation === occupierNation) ||
            Town.areAllied(attackingTown, occupier)
        ) {
            if (Town.areEnemies(attackingTown, chunk.occupier)) {
                return true
            }
        }

        return false
    }

    // check that chunk valid, either:
    // 1. next to wilderness
    // 2. next to occupied chunk (by town or allies)
    internal fun chunkIsAtEdge(chunk: TerritoryChunk, attackingTown: Town): Boolean {
        val coord = chunk.coord

        val chunkNorth = TerritoryChunk.fromCoord(Coord(coord.x, coord.z - 1))
        val chunkSouth = TerritoryChunk.fromCoord(Coord(coord.x, coord.z + 1))
        val chunkWest = TerritoryChunk.fromCoord(Coord(coord.x - 1, coord.z))
        val chunkEast = TerritoryChunk.fromCoord(Coord(coord.x + 1, coord.z))

        return canAttackFromNeighborChunk(chunkNorth, attackingTown) ||
            canAttackFromNeighborChunk(chunkSouth, attackingTown) ||
            canAttackFromNeighborChunk(chunkWest, attackingTown) ||
            canAttackFromNeighborChunk(chunkEast, attackingTown)
    }

    /**
     * conditions for attacking a chunk relative to a neighbor chunk
     */
    internal fun canAttackFromNeighborChunk(neighborChunk: TerritoryChunk?, attacker: Town): Boolean {
        // no territory here
        if (neighborChunk === null) {
            return true
        }

        val attackerNation = attacker.nation

        val neighborTerritory = neighborChunk.territory
        val neighborTown = neighborTerritory.town
        val neighborTerritoryOccupier = neighborTerritory.occupier
        val neighborChunkOccupier = neighborChunk.occupier

        // territory is unoccupied
        if (neighborTown === null) {
            return true
        }

        // neighbor is your town and occupier is friendly
        if (neighborTown === attacker) {
            if (neighborTerritoryOccupier === null) {
                return true
            } else if (Town.areAllied(attacker, neighborTerritoryOccupier)) {
                return true
            }
        }

        // you are neighbor territory occupier or an ally is the occupier
        if (neighborTerritoryOccupier === attacker || Town.areAllied(attacker, neighborTerritoryOccupier)) {
            return true
        }

        // you or an ally is occupying the neighboring chunk
        if (neighborChunkOccupier === attacker || Town.areAllied(attacker, neighborChunkOccupier)) {
            return true
        }

        if (attackerNation !== null) {
            val neighborNation = neighborTown.nation
            val neighborTerritoryOccupierNation = neighborTerritoryOccupier?.nation
            val neighborChunkOccupierNation = neighborChunk.occupier?.nation

            // additional neighbor town check, when occupier is in same nation (somehow)
            if (neighborTown === attacker && neighborNation === neighborTerritoryOccupierNation) {
                return true
            }

            // neighboring chunk belongs to nation and occupied by friendly
            if (attackerNation === neighborNation) {
                if (neighborTerritoryOccupier === null) {
                    return true
                } else if (Town.areAllied(attacker, neighborTerritoryOccupier)) {
                    return true
                }
            }

            if (attackerNation === neighborTerritoryOccupierNation) {
                return true
            }

            if (attackerNation === neighborChunkOccupierNation) {
                return true
            }
        }

        return false
    }

    /**
     * Create/update a flag attack beacon
     * Uses an fast editing session with single packet send and
     * no lighting updates
     * - with block.setType(): takes ~2 ms to update
     * - with edit session: takes ~200-300 us to update
     */
    internal fun createAttackBeacon(
        skyBeaconColorBlocks: MutableList<BlockVec>,
        skyBeaconWireframeBlocks: MutableList<BlockVec>,
        coord: Coord,
        flagBaseY: Int,
    ) {
        // get starting corner
        val size = skyBeaconSize
        val startPositionInChunk: Int = (16 - size) / 2
        val x0: Int = coord.x * 16 + startPositionInChunk
        val z0: Int = coord.z * 16 + startPositionInChunk
        val y0: Int = maxOf(flagBaseY + Nodes.config.flagBeaconSkyLevel, Nodes.config.flagBeaconMinSkyLevel)
        val xEnd: Int = x0 + size - 1
        val zEnd: Int = z0 + size - 1
        val yEnd: Int = minOf(255, y0 + size) // truncate at map limit

        for (y in y0..yEnd) {
            for (x in x0..xEnd) {
                for (z in z0..zEnd) {
                    val blockPos = BlockVec(x, y, z)
                    val block = MinecraftServer.getInstanceManager().instances.first().getBlock(x, y, z)
                    if (block == Block.AIR || SKY_BEACON_BLOCKS.contains(block)) {
                        if (((y == y0 || y == yEnd) && (x == x0 || x == xEnd || z == z0 || z == zEnd)) ||
                            // end caps, edges glowstone
                            ((x == x0 || x == xEnd) && (z == z0 || z == zEnd))
                        ) { // middle section corners glowstone
                            // block.setType(BEACON_EDGE_BLOCK) // slow
                            skyBeaconWireframeBlocks.add(blockPos)
                            MinecraftServer.getInstanceManager().instances.first().setBlock(blockPos, SKY_BEACON_FRAME_BLOCK)
                        } else { // color block
                            // setFlagAttackColorBlock(block, progress) // slow
                            skyBeaconColorBlocks.add(blockPos)
                            MinecraftServer.getInstanceManager().instances.first().setBlock(blockPos, SKY_BEACON_BLOCK)
                        }
                    }
                }
            }
        }
    }

    // cleanup attack instance, then dispatch signal
    // that attack cancelled (was defended)
    // (runs on main thread)
    // TODO: signal event that chunk defended (broadcast message)
    internal fun cancelAttack(attack: Attack) {
        if (!attack.markEnded()) return
        try {
            synchronized(Nodes.occupationPersistenceLock) {
                cancelAttackOnce(attack)
            }
        } finally {
            notifyColonizationAttackEnded(attack)
        }
    }

    private fun cancelAttackOnce(attack: Attack) {
        // remove status from territory chunk
        val chunk = TerritoryChunk.fromCoord(attack.coord)
        chunk?.attacker = null

        // remove progress bar from player
        attack.progressBar.removeViewer(Audiences.all())

        // remove claim flag
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagTorch, Block.AIR)
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagBlock, Block.AIR)
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagBase, Block.AIR)

        // remove sky beacon
        for (block in attack.skyBeaconWireframeBlocks) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(block, Block.AIR)
        }
        for (block in attack.skyBeaconColorBlocks) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(block, Block.AIR)
        }

        // remove text display
        attack.textDisplay.remove()

        // remove attack instance references
        attackers.get(attack.attacker)?.remove(attack)
        chunkToAttacker.remove(attack.coord)
        blockToAttacker.remove(attack.flagBlock)
        Resident.renderMinimaps()

        // mark save needed
        needsSave = true
    }

    /**
     * finish attack instance and capture chunk
     * - set chunk occupation status
     * - dispatch signal that attack finished
     * (runs on main thread)
     * different results:
     *   1. attacking enemy chunk -> capture chunk
     *   2. attacking enemy home chunk -> capture territory
     *   3. attacking town/ally occupied chunk -> capture chunk
     *   4. attacking town/ally home chunk -> recapture territory
     */
    internal fun finishAttack(attack: Attack) {
        if (!attack.markEnded()) return
        try {
            synchronized(Nodes.occupationPersistenceLock) {
                finishAttackOnce(attack)
            }
        } finally {
            notifyColonizationAttackEnded(attack)
        }
    }

    private fun finishAttackOnce(attack: Attack) {
        val messageContext = if (attack.mode == AttackMode.COLONIZATION) "[Colonization]" else "[War]"

        // remove progress bar from player
        attack.progressBar.removeViewer(Audiences.all())

        // remove claim flag
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagTorch, Block.AIR)
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagBlock, Block.AIR)
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagBase, Block.AIR)

        // remove sky beacon
        for (block in attack.skyBeaconWireframeBlocks) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(block, Block.AIR)
        }
        for (block in attack.skyBeaconColorBlocks) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(block, Block.AIR)
        }

        // remove town and cap progress textdisplay
        attack.textDisplay.remove()

        // remove attack instance references
        attackers.get(attack.attacker)?.remove(attack)
        chunkToAttacker.remove(attack.coord)
        blockToAttacker.remove(attack.flagBlock)

        // mark that save required
        needsSave = true

        // chunk should not be null unless territory swapped
        // out during attack and chunks were modified in new territory
        val chunk = TerritoryChunk.fromCoord(attack.coord)
        if (chunk == null || chunk.territory !== attack.targetTerritory) {
            println("finishAttack(): TerritoryChunk at ${attack.coord} is null")
            Resident.renderMinimaps()
            return
        }

        if (attack.mode == AttackMode.WAR && chunk.coord == chunk.territory.core && !canAnnexTerritories) {
            chunk.attacker = null
            Resident.renderMinimaps()
            return
        }

        // handle occupation state of chunk
        // if chunk is core chunk of territory, attacking town occupies territory
        if (chunk.coord == chunk.territory.core) {
            synchronized(Nodes.occupationPersistenceLock) {
                val territory = chunk.territory
                val territoryTown = territory.town
                val attacker = Resident.fromUuid(attack.attacker)
                val attackerTown = attack.town
                val attackerNation = attackerTown.nation

                // cleanup territory chunks
                for (coord in territory.chunks) {
                    val territoryChunk = TerritoryChunk.fromCoord(coord)
                    if (territoryChunk != null) {
                        // cancel any concurrent attacks in this territory
                        chunkToAttacker.get(territoryChunk.coord)?.cancel()

                        // clear occupy/attack status from chunks
                        territoryChunk.attacker = null
                        territoryChunk.occupier = null

                        // remove from internal list of occupied chunks
                        occupiedChunks.remove(territoryChunk.coord)
                        colonizedChunks.remove(territoryChunk.coord)
                    }
                }

                // handle re-capturing your own territory, nation territory, or ally territory from enemy
                if (territoryTown === attackerTown ||
                    (attackerNation !== null && attackerNation === territoryTown?.nation) ||
                    Town.areAllied(attackerTown, territoryTown)
                ) {
                    val occupier = territory.occupier
                    Town.release(territory)
                    Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} liberated territory (id=${territory.id}) from ${occupier?.name}!")
                }
                // captured enemy territory
                else {
                    Town.capture(attackerTown, territory, commitWarState = false)
                    if (attack.mode == AttackMode.COLONIZATION) {
                        // A completed colony must retain the same off-war control
                        // that partial colony chunks have. Persist an occupier on
                        // every chunk so the provenance survives war.json reloads.
                        for (coord in territory.chunks) {
                            TerritoryChunk.fromCoord(coord)?.let { territoryChunk ->
                                territoryChunk.occupier = attackerTown
                                occupiedChunks.add(coord)
                                colonizedChunks.add(coord)
                            }
                        }
                        Resident.renderMinimaps()
                    }
                    commitTerritoryOccupation(
                        territory,
                        attackerTown,
                        colonized = attack.mode == AttackMode.COLONIZATION,
                    )
                    val action = if (attack.mode == AttackMode.COLONIZATION) "colonized" else "captured"
                    Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} $action territory (id=${territory.id}) from ${territoryTown?.name}!")
                    if (territoryTown != null && shouldAnnexTown(territoryTown, territory)) {
                        val defeatedTownName = territoryTown.name
                        Town.annex(attackerTown, territoryTown)
                        Message.broadcast(
                            "${ChatColor.DARK_RED}[Conquest] ${attackerTown.name} annexed $defeatedTownName; " +
                                "${attacker?.name ?: attackerTown.name} made the decisive capture!",
                        )
                    }
                }
            }
        }
        // else, attacking normal chunk cases:
        // 1. your town, chunk captured by enemy -> liberating, remove flag
        // 2. your town (occupied) -> liberating, put flag
        // 3. territory occupied by your town, captured -> liberating, remove flag
        // 4. enemy town, empty chunk -> attacking, put flag
        else {
            val town = chunk.territory.town
            val occupier = chunk.territory.occupier
            val attacker = Resident.fromUuid(attack.attacker)

            chunk.attacker = null

            if (town === attack.town) {
                // re-capturing territory from occupier
                if (occupier !== null) {
                    chunk.occupier = town
                    occupiedChunks.add(chunk.coord)
                    colonizedChunks.remove(chunk.coord)

                    Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} liberated chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${occupier.name}!")
                }
                // must be defending captured chunk
                else {
                    val chunkOccupier = chunk.occupier

                    chunk.occupier = null
                    occupiedChunks.remove(chunk.coord)
                    colonizedChunks.remove(chunk.coord)

                    if (chunkOccupier !== null) {
                        Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${chunkOccupier.name}!")
                    }
                }
            } else if (occupier === attack.town && chunk.occupier !== null) {
                val chunkOccupier = chunk.occupier
                if (attack.mode == AttackMode.COLONIZATION) {
                    // restore explicit provenance for a recaptured piece of a
                    // full colony so its control still works outside global war
                    chunk.occupier = attack.town
                    occupiedChunks.add(chunk.coord)
                    colonizedChunks.add(chunk.coord)
                } else {
                    chunk.occupier = null
                    occupiedChunks.remove(chunk.coord)
                    colonizedChunks.remove(chunk.coord)
                }

                Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${chunkOccupier?.name}!")
            } else {
                chunk.occupier = attack.town
                occupiedChunks.add(chunk.coord)
                if (attack.mode == AttackMode.COLONIZATION) {
                    colonizedChunks.add(chunk.coord)
                } else {
                    colonizedChunks.remove(chunk.coord)
                }

                Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} captured chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${chunk.territory.town?.name}!")
            }

            // update minimaps
            Resident.renderMinimaps()
        }
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

    // update tick for attack instance
    internal fun attackTick(attack: Attack) {
        if (attack.mode == AttackMode.COLONIZATION && !Colonization.attackRemainsAuthorized(attack)) {
            attack.cancel()
            return
        }
        val progress = attack.progress + ATTACK_TICK

        if (progress >= attack.attackTime) {
            // cancel thread, then finalize attack
            attack.thread.cancel()
            finishAttack(attack)
        } else { // update
            attack.progress = progress

            // update boss bar progress
            val progressNormalized: Float = progress.toFloat() / attack.attackTime.toFloat()
            attack.progressBar.progress(progressNormalized)

            // update progress text
            for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                attack.textDisplay.update(player)
            }
        }
    }

    // intended to run on PlayerJoin event
    // if war enabled and player has attacks,
    // send progress bars to player
    fun sendWarProgressBarToPlayer(player: Player) {
        val uuid = player.uuid

        // add attack to list of attacks by attacker
        val currentAttacks = attackers.get(uuid)
        if (currentAttacks != null) {
            for (attack in currentAttacks) {
                attack.progressBar.addViewer(player)
            }
        }
    }

    private fun startSaveTask(restart: Boolean = false) {
        if (restart) {
            saveTask?.cancel()
            saveTask = null
        }
        if (saveTask != null) return
        saveTask = MinecraftServer.getSchedulerManager()
            .buildTask(SaveLoop)
            .delay(TaskSchedule.tick(saveTaskPeriod))
            .repeat(TaskSchedule.tick(saveTaskPeriod))
            .schedule()
    }
}
