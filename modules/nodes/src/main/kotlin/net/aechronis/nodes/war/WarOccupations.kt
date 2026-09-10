package net.aechronis.nodes.war

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.serdes.WarSerializer
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.timer.TaskSchedule
import java.util.UUID

/** Restores and journals occupation state, and schedules periodic war saves. */
internal class WarOccupations(
    private val state: FlagWarState,
    private val presentation: WarPresentation,
) {
    fun save() {
        synchronized(Nodes.occupationPersistenceLock) {
            if (!state.needsSave && !state.territoryOccupationJournalDirty) return
            try {
                if (state.territoryOccupationJournalDirty) {
                    flushTerritoryOccupationJournal()
                    state.needsSave = false
                } else {
                    state.needsSave = false
                    WarSerializer.save(true).whenComplete { _, error ->
                        if (error != null) state.needsSave = true
                    }
                }
            } catch (error: Exception) {
                state.needsSave = true
                System.err.println("Failed to save war state: ${error.message}")
            }
        }
    }

    fun loadOccupiedChunk(townId: UUID, coord: Coord) {
        val town = Town.fromUuid(townId)
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
        state.occupiedChunks.add(terrChunk.coord)
    }

    fun loadColonizedChunk(coord: Coord) {
        val chunk = TerritoryChunk.fromCoord(coord) ?: return
        if (chunk.occupier != null && state.occupiedChunks.contains(coord)) state.colonizedChunks.add(coord)
    }

    fun loadTerritoryOccupation(
        territoryId: TerritoryId,
        occupierId: UUID?,
        colonized: Boolean,
    ) {
        state.territoryOccupations[territoryId] = TerritoryOccupationState(occupierId, colonized)
        val territory = Territory.fromId(territoryId) ?: return
        val occupier = occupierId?.let(Town::fromUuid)
        if (occupierId != null && occupier == null) {
            System.err.println("[Nodes] Ignoring unknown town $occupierId in territory occupation $territoryId")
            return
        }
        Town.restoreOccupation(territory, occupier)
    }

    fun commitTerritoryOccupation(
        territory: Territory,
        occupier: Town?,
        colonized: Boolean,
        flushJournal: Boolean = true,
    ) = synchronized(Nodes.occupationPersistenceLock) {
        if (colonized && occupier != null) {
            territory.chunks.forEach { coord ->
                TerritoryChunk.fromCoord(coord)?.let { chunk ->
                    chunk.occupier = occupier
                    state.occupiedChunks.add(coord)
                    state.colonizedChunks.add(coord)
                }
            }
            presentation.requestMinimapRefresh()
        }
        state.territoryOccupations[territory.id] = TerritoryOccupationState(occupier?.uuid, colonized)
        state.territoryOccupationJournalDirty = true
        if (flushJournal) flushTerritoryOccupationJournal()
    }

    /** Synchronously journals occupation transitions before a towns.json snapshot can expose them. */
    fun flushTerritoryOccupationJournal() = synchronized(Nodes.occupationPersistenceLock) {
        if (!state.territoryOccupationJournalDirty) return@synchronized
        WarSerializer.save(false)
        state.territoryOccupationJournalDirty = false
    }

    /** Clears chunk-level progress; the caller owns the territory occupation transition. */
    fun clearTerritoryOccupation(territory: Territory) {
        territory.chunks.forEach { coord ->
            state.chunkToAttacker[coord]?.cancel()
            TerritoryChunk.fromCoord(coord)?.let { chunk ->
                chunk.attacker = null
                chunk.occupier = null
            }
            state.occupiedChunks.remove(coord)
            state.colonizedChunks.remove(coord)
        }
        state.needsSave = true
        presentation.requestMinimapRefresh()
    }

    fun clearOccupationsBy(town: Town) {
        state.chunkToAttacker.values
            .filter { attack -> attack.town === town || attack.targetTerritory.town === town }
            .toList()
            .forEach(Attack::cancel)
        state.occupiedChunks.toList().forEach { coord ->
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk?.occupier === town) {
                chunk.occupier = null
                state.occupiedChunks.remove(coord)
                state.colonizedChunks.remove(coord)
            }
        }
        state.needsSave = true
        Resident.renderMinimaps()
    }

    fun stopColonizationCampaign(
        attacker: UUID,
        attackingTown: Town,
        targetTown: Town,
        abandonCompletedProgress: Boolean,
    ) = synchronized(Nodes.occupationPersistenceLock) {
        state.chunkToAttacker.values
            .filter { attack ->
                attack.mode == AttackMode.COLONIZATION &&
                    attack.town === attackingTown &&
                    attack.targetTown === targetTown &&
                    (abandonCompletedProgress || attack.attacker == attacker)
            }.toList()
            .forEach(Attack::cancel)

        if (!abandonCompletedProgress) return@synchronized

        var changed = false
        Territory.all()
            .filter { territory -> territory.town === targetTown }
            .forEach { territory ->
                val occupation = state.territoryOccupations[territory.id]
                val territoryColonizedByAttacker = territory.occupier === attackingTown &&
                    occupation?.occupierId == attackingTown.uuid &&
                    occupation.colonized

                territory.chunks.forEach { coord ->
                    val chunk = TerritoryChunk.fromCoord(coord) ?: return@forEach
                    if (coord !in state.colonizedChunks || chunk.occupier !== attackingTown) return@forEach
                    chunk.occupier = null
                    state.occupiedChunks.remove(coord)
                    state.colonizedChunks.remove(coord)
                    changed = true
                }

                if (territoryColonizedByAttacker) {
                    Town.restoreOccupation(territory, null)
                    state.territoryOccupations[territory.id] = TerritoryOccupationState(null, colonized = false)
                    state.territoryOccupationJournalDirty = true
                    changed = true
                }
            }

        if (changed) {
            state.needsSave = true
            Resident.renderMinimaps()
            WarSerializer.save(false)
            state.territoryOccupationJournalDirty = false
            state.needsSave = false
        }
    }

    fun loadSkirmishTarget(nationId: UUID, territoryId: TerritoryId) {
        if (!state.enabled || !state.canOnlyAttackBorders) return
        if (Nation.fromUuid(nationId) == null) {
            System.err.println("[Nodes] Ignoring skirmish target for unknown nation $nationId")
            return
        }
        if (Territory.fromId(territoryId) == null) {
            System.err.println("[Nodes] Ignoring unknown skirmish target territory $territoryId")
            return
        }
        state.skirmishTargetsByNation[nationId] = territoryId
    }

    fun loadDefeatedTown(townId: UUID) {
        if (state.enabled && Town.fromUuid(townId) != null) state.townsDefeatedThisWar.add(townId)
    }

    fun startSaveTask(restart: Boolean = false) {
        if (restart) {
            state.saveTask?.cancel()
            state.saveTask = null
        }
        if (state.saveTask != null) return
        state.saveTask = ModuleScheduler
            .buildTask(::save)
            .delay(TaskSchedule.tick(state.saveTaskPeriod))
            .repeat(TaskSchedule.tick(state.saveTaskPeriod))
            .schedule()
    }

    fun isColonized(coord: Coord): Boolean = state.colonizedChunks.contains(coord)
}
