package net.aechronis.nodes.war

import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.TerritoryId
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.block.Block
import net.minestom.server.timer.Task
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal data class TerritoryOccupationState(
    val occupierId: UUID?,
    val colonized: Boolean,
)

internal data class SkirmishTargetSelection(
    val nationId: UUID,
    val territoryId: TerritoryId,
)

internal enum class TownDefeatOutcome {
    ALREADY_DEFEATED_THIS_WAR,
    LOST_LIFE,
    ANNEXED,
    FINAL_LIFE_PROTECTED,
}

/** Mutable state shared by the war lifecycle, rules, persistence, and active attacks. */
internal class FlagWarState {
    var enabled: Boolean = false
    var deathWar: Boolean = false
    val isDeathWar: Boolean get() = enabled && deathWar
    var canAnnexTerritories: Boolean = false
    var canOnlyAttackBorders: Boolean = false
    var destructionEnabled: Boolean = false
    var saveTaskPeriod: Int = 20
    val flagBlocks: MutableSet<Block> = mutableSetOf()
    var skyBeaconSize: Int = 6

    val attackers: ConcurrentHashMap<UUID, CopyOnWriteArrayList<Attack>> = ConcurrentHashMap()
    val chunkToAttacker: ConcurrentHashMap<Coord, Attack> = ConcurrentHashMap()
    val blockToAttacker: ConcurrentHashMap<BlockVec, Attack> = ConcurrentHashMap()
    val occupiedChunks: MutableSet<Coord> = ConcurrentHashMap.newKeySet()
    val colonizedChunks: MutableSet<Coord> = ConcurrentHashMap.newKeySet()
    val territoryOccupations: MutableMap<TerritoryId, TerritoryOccupationState> = ConcurrentHashMap()
    val skirmishTargetsByNation: MutableMap<UUID, TerritoryId> = ConcurrentHashMap()
    val townsDefeatedThisWar: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    var territoryOccupationJournalDirty: Boolean = false

    @Volatile
    var needsSave: Boolean = false
    var saveTask: Task? = null
    var attackTask: Task? = null
}
