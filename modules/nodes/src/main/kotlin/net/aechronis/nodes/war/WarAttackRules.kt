package net.aechronis.nodes.war

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.ErrorSkirmishNationRequired
import net.aechronis.nodes.constants.ErrorSkirmishTargetLocked
import net.aechronis.nodes.constants.ErrorSkirmishTargetSelectionRole
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.Town
import java.util.UUID

/** Attack eligibility, skirmish target selection, and capture timing rules. */
internal class WarAttackRules(private val state: FlagWarState) {
    fun canCaptureTerritoryCore(): Boolean = state.canAnnexTerritories || state.canOnlyAttackBorders

    fun canAnnexDefeatedTown(mode: AttackMode): Boolean = mode != AttackMode.WAR || state.canAnnexTerritories

    // check if territory is a border territory of a town, requirements:
    // any adjacent territory is not of the same town
    fun isBorderTerritory(territory: Territory): Boolean {
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
            val neighborTerritory = Territory.fromId(neighborTerritoryId)
            if (neighborTerritory !== null && neighborTerritory.town !== territoryTown) {
                return true
            }
        }

        return false
    }

    /** A successful territory capture defeats its town by taking its home. */
    fun shouldAnnexTown(
        defeatedTown: Town,
        capturedTerritory: Territory,
    ): Boolean {
        if (capturedTerritory.town !== defeatedTown) return false
        // Capturing another territory must not occupy a registered warzone or
        // bypass its independent scoring mechanics.
        if (Warzone.ownsRegisteredZone(defeatedTown)) return false
        return capturedTerritory.id == defeatedTown.home
    }

    // check if chunk was already captured
    // 1. territory occupied by town or allies and chunk not occupied
    // 2. chunk occupied by town or allies
    fun chunkAlreadyCaptured(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        val territoryOccupier = territory.occupier
        val chunkOccupier = chunk.occupier

        if (territoryOccupier === attackingTown || Town.areAllied(attackingTown, territoryOccupier)) {
            if (!townIsWarOpponent(attackingTown, chunkOccupier)) {
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

    fun chunkAlreadyColonizedBy(
        chunk: TerritoryChunk,
        territory: Territory,
        attackingTown: Town,
    ): Boolean {
        val effectiveOccupier = chunk.occupier ?: territory.occupier ?: return false
        return effectiveOccupier === attackingTown || Town.areAllied(attackingTown, effectiveOccupier)
    }

    /** Wars require enemies (including deathwar hostility); skirmishes allow any non-allied town. */
    fun townIsWarOpponent(attackingTown: Town, otherTown: Town?): Boolean {
        if (otherTown == null) return false
        return if (state.canOnlyAttackBorders) {
            !Town.areAllied(attackingTown, otherTown)
        } else {
            Town.areEnemies(attackingTown, otherTown)
        }
    }

    /**
     * Validate the nation-wide target lock for a border skirmish. A null
     * selection means either this is not a skirmish or the nation already
     * selected this territory. A non-null selection is committed only after
     * the flag passes every other attack validation.
     */
    fun prepareSkirmishTargetSelection(
        attacker: UUID,
        attackingTown: Town,
        territory: Territory,
    ): Result<SkirmishTargetSelection?> {
        if (!state.canOnlyAttackBorders) return Result.success(null)

        val nation = attackingTown.nation ?: return Result.failure(ErrorSkirmishNationRequired)
        val selectedTerritory = state.skirmishTargetsByNation[nation.uuid]
        if (selectedTerritory != null) {
            return if (selectedTerritory == territory.id) {
                Result.success(null)
            } else {
                Result.failure(ErrorSkirmishTargetLocked)
            }
        }

        val resident = Resident.fromUuid(attacker)
        val canSelect = resident?.town === attackingTown &&
            (resident === attackingTown.leader || attackingTown.officers.contains(resident))
        if (!canSelect) return Result.failure(ErrorSkirmishTargetSelectionRole)

        return Result.success(SkirmishTargetSelection(nation.uuid, territory.id))
    }

    fun commitSkirmishTargetSelection(selection: SkirmishTargetSelection): Boolean {
        val existing = state.skirmishTargetsByNation[selection.nationId]
        check(existing == null || existing == selection.territoryId) {
            "Nation ${selection.nationId} already selected skirmish territory $existing"
        }
        if (existing != null) return false
        state.skirmishTargetsByNation[selection.nationId] = selection.territoryId
        return true
    }

    fun skirmishTarget(attackingTown: Town): Territory? = attackingTown.nation
        ?.let { nation -> state.skirmishTargetsByNation[nation.uuid] }
        ?.let(Territory::fromId)

    // Check that a chunk belongs to an opponent and can be attacked:
    // 1. belongs to an opposing town
    // 2. town chunk occupied by an opponent
    // 3. allied chunk occupied by an opponent
    // 4. town's occupied territory, chunk occupied by an opponent
    // 5. ally's occupied territory, chunk occupied by an opponent
    fun chunkIsAttackable(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        if (townIsWarOpponent(attackingTown, territory.town)) {
            return true
        }

        val attackingNation = attackingTown.nation
        val territoryNation = territory.town?.nation

        // your town, nation, or ally town chunk occupied by an opponent
        if ((territory.town === attackingTown) ||
            (attackingNation !== null && attackingNation === territoryNation) ||
            (Town.areAllied(attackingTown, territory.town))
        ) {
            if (townIsWarOpponent(attackingTown, territory.occupier)) {
                return true
            }
            if (townIsWarOpponent(attackingTown, chunk.occupier)) {
                return true
            }
        }

        // your occupied territory or ally's occupied territory
        // chunk occupied by an opponent
        val occupier = territory.occupier
        val occupierNation = occupier?.nation
        if (occupier === attackingTown ||
            (attackingNation !== null && attackingNation === occupierNation) ||
            Town.areAllied(attackingTown, occupier)
        ) {
            if (townIsWarOpponent(attackingTown, chunk.occupier)) {
                return true
            }
        }

        return false
    }

    // check that chunk valid, either:
    // 1. next to wilderness
    // 2. next to occupied chunk (by town or allies)
    fun chunkIsAtEdge(chunk: TerritoryChunk, attackingTown: Town): Boolean {
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
    fun canAttackFromNeighborChunk(neighborChunk: TerritoryChunk?, attacker: Town): Boolean {
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

    fun warAttackRemainsAuthorized(attack: Attack): Boolean {
        if (!state.enabled) return false
        val chunk = TerritoryChunk.fromCoord(attack.coord) ?: return false
        return chunk.territory === attack.targetTerritory &&
            chunkIsAttackable(chunk, chunk.territory, attack.town) &&
            !chunkAlreadyCaptured(chunk, chunk.territory, attack.town)
    }

    fun attackTime(attackingTown: Town, territory: Territory): Long {
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
        return attackTime.toLong()
    }
}
