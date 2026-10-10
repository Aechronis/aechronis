package net.aechronis.nodes.serdes

import net.aechronis.nodes.colonization.AiTownConfig
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.MiningBoostManager
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Plot
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.Waypoint
import net.aechronis.nodes.utils.Color
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.Material
import java.util.UUID

/** Decoded data remains detached from the live registries until the whole world is ready. */
internal data class TownsLoadState(
    val residents: List<ResidentLoadState>,
    val towns: List<TownLoadState>,
    val nations: List<NationLoadState>,
    val miningBoost: MiningBoostSaveState,
) {
    fun prepare(territories: List<Territory>): TownsLoadState {
        val territoriesById = territories.associateBy { it.id }
        val townNames = towns.mapTo(hashSetOf()) { it.name }
        nations.forEach { nation ->
            require(nation.capitalName in townNames) {
                "Cannot load nation '${nation.name}': capital town '${nation.capitalName}' is missing"
            }
        }
        return copy(
            towns = towns.map { town ->
                val home = requireNotNull(territoriesById[TerritoryId(town.homeId)]) {
                    "Cannot load town '${town.name}': home territory ${town.homeId} is missing from the world definition"
                }
                town.copy(spawn = town.spawn ?: Territory.defaultSpawnLocation(home))
            },
        )
    }

    /** Required references were validated against the replacement world, not the old registries. */
    fun install() {
        MiningBoostManager.load(miningBoost)
        residents.forEach { resident ->
            Resident.load(
                uuid = resident.uuid,
                name = resident.name,
                trusted = resident.trusted,
                waypoints = resident.waypoints,
                waypointVisibility = resident.waypointVisibility,
                minimapEnabled = resident.minimapEnabled,
                minimapPosition = resident.minimapPosition,
                minimapShiftEnabled = resident.minimapShiftEnabled,
                minimapNorthLocked = resident.minimapNorthLocked,
                townJoinLockedUntil = resident.townJoinLockedUntil,
            )
        }
        // The domain loaders intentionally skip absent optional residents, claims and member towns.
        towns.forEach(Town::load)
        val loadedNations = nations.map { nation ->
            Nation.load(
                uuid = nation.uuid,
                name = nation.name,
                capitalName = nation.capitalName,
                color = nation.color,
                towns = nation.towns,
                rallyCap = nation.rallyCap,
                longName = nation.longName,
                flagUrl = nation.flagUrl,
            )
        }
        loadedNations.zip(nations).forEach { (nation, state) ->
            Nation.loadDiplomacy(nation, state.allies, state.enemies)
        }
    }
}

internal data class ResidentLoadState(
    val uuid: UUID,
    val name: String,
    val trusted: Boolean,
    val waypoints: List<Waypoint>,
    val waypointVisibility: Map<String, Boolean>,
    val minimapEnabled: Boolean,
    val minimapPosition: MinimapPosition,
    val minimapShiftEnabled: Boolean,
    val minimapNorthLocked: Boolean,
    val townJoinLockedUntil: Long?,
)

internal data class TownLoadState(
    val uuid: UUID,
    val name: String,
    val leader: UUID?,
    val homeId: Int,
    val spawn: Pos?,
    val color: Color?,
    val residents: List<UUID>,
    val officers: List<UUID>,
    val territoryIds: List<Int>,
    val capturedTerritoryIds: List<Int>,
    val annexedTerritoryIds: List<Int>,
    val income: Map<Material, Int>,
    val permissions: Map<TownPermissions, Set<PermissionsGroup>>,
    val protectedBlocks: Set<BlockVec>,
    val plots: List<Plot.PlotSaveState>,
    val aiConfig: AiTownConfig,
    val lives: Int?,
    val capitalLifeGranted: Boolean,
    val lifeRevision: Long,
    val coatOfArmsUrl: String?,
)

internal data class NationLoadState(
    val uuid: UUID,
    val name: String,
    val capitalName: String,
    val color: Color?,
    val towns: List<String>,
    val rallyCap: Int?,
    val longName: String?,
    val flagUrl: String?,
    val allies: List<String>,
    val enemies: List<String>,
)
