package net.aechronis.nodes.permissions

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.colonization.Colonization
import net.aechronis.nodes.constants.INTERACTIVE_BLOCKS
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.objects.Plot
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block

internal object LandAccess {
    fun check(
        player: Player,
        position: BlockVec,
        action: LandAccessAction,
        block: Block? = null,
    ): LandAccessDecision = LandAccessPolicy.evaluate(action, WorldLandAccessContext(player, position, action, block))
}

private class WorldLandAccessContext(
    private val player: Player,
    private val position: BlockVec,
    private val action: LandAccessAction,
    private val block: Block?,
) : LandAccessContext {
    private val territory = Territory.fromBlock(position.blockX, position.blockZ)
    private val town = territory?.town
    private val resident by lazy { Resident.fromPlayer(player) }
    private val territoryChunk by lazy { TerritoryChunk.fromBlock(position.blockX, position.blockZ) }
    private val warzoneTown by lazy {
        territoryChunk?.let { chunk -> territory?.let { warzoneOccupier(it, chunk) } }
    }

    override val hasTown: Boolean get() = town != null
    override val hasResident: Boolean get() = resident != null
    override val wildernessAllowed: Boolean get() = hasWildernessPermissions(territory)
    override val bypass: Boolean get() = checkNotNull(resident).hasTownPermissionBypass()
    override val interactiveBlock: Boolean get() = INTERACTIVE_BLOCKS.any { checkNotNull(block).compare(it) }
    override val warzonePermission: Boolean?
        get() = warzoneTown?.let { hasTownPermissions(action.permission, it, checkNotNull(resident)) }
    override val warAllowed: Boolean
        get() = territoryChunk?.let { hasWarPermissions(checkNotNull(resident), checkNotNull(territory), it) } == true
    override val plotPermission: Boolean?
        get() = Plot.at(checkNotNull(town), position.blockX, position.blockY, position.blockZ)
            ?.let { getPlotPermission(action.permission, it, checkNotNull(resident), checkNotNull(town)) }
    override val townAllowed: Boolean
        get() = hasTownPermissions(action.permission, checkNotNull(town), checkNotNull(resident))
    override val occupierAllowed: Boolean
        get() = territory?.occupier?.let {
            hasOccupierPermissions(action.permission, checkNotNull(town), it, checkNotNull(resident))
        } == true
    override val protectedChestAllowed: Boolean
        get() {
            val controllingTown = warzoneTown ?: checkNotNull(town)
            return position !in controllingTown.protectedBlocks || checkNotNull(resident).hasTownProtectedChestPermissions(controllingTown)
        }
    override val flagPlacementAllowed: Boolean
        get() {
            val canColonizeHere = checkNotNull(resident).town?.let { residentTown ->
                Colonization.isAuthorized(player.uuid, residentTown, town)
            } == true
            return (FlagWar.enabled || canColonizeHere || Warzone.isActive(checkNotNull(territory))) && Nodes.config.flagBlocks.contains(block)
        }
}

/**
 * Permissions for unclaimed territories or empty areas (no territories)
 */
private fun hasWildernessPermissions(territory: Territory?): Boolean {
    if (territory !== null && Nodes.config.canInteractInUnclaimed) {
        return true
    } else if (Nodes.config.canInteractInEmpty) {
        return true
    }

    return false
}

/**
 * Default permissions check for town:
 * perms: town permissions type
 * town: town
 * player: player interacting in town
 */
private fun hasTownPermissions(perms: TownPermissions, town: Town, player: Resident): Boolean {
    if (town.permissions[perms].contains(PermissionsGroup.TOWN) && player.town === town) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.TRUSTED) && player.town === town && player.trusted) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.NATION) && town.nation !== null && player.nation === town.nation) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.ALLY) && town.nation !== null && player.town?.nation !== null && town.nation!!.allies.contains(player.town!!.nation)) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.OUTSIDER)) {
        return true
    }

    return false
}

/**
 * Returns a plot override, or null when the plot should inherit town permissions.
 */
private fun getPlotPermission(
    permission: TownPermissions,
    plot: net.aechronis.nodes.objects.Plot,
    resident: Resident,
    town: Town,
): Boolean? {
    plot.playerPermission(resident.uuid, permission)?.let { return it }

    val groupMatches = listOf(
        PermissionsGroup.TOWN to (resident.town === town),
        PermissionsGroup.TRUSTED to (resident.town === town && resident.trusted),
        PermissionsGroup.NATION to (town.nation !== null && resident.nation === town.nation),
        PermissionsGroup.ALLY to (
            town.nation !== null &&
                resident.town?.nation !== null &&
                town.nation!!.allies.contains(resident.town!!.nation)
            ),
        PermissionsGroup.OUTSIDER to true,
    )

    for ((group, matches) in groupMatches) {
        if (matches) {
            plot.groupPermission(group, permission)?.let { return it }
        }
    }

    return null
}

/**
 * Permissions check for a town's territory occupied by another town:
 * perms: town permissions type
 * town: town that owns the territory
 * occupier: town that is occupier of the territory
 * player: player interacting in the territory
 */
private fun hasOccupierPermissions(perms: TownPermissions, town: Town, occupier: Town, player: Resident): Boolean = if (Nodes.config.allowControlInOccupiedTownList.contains(town.uuid)) {
    hasTownPermissions(perms, occupier, player)
} else {
    false
}

/**
 * Warzones run outside global FlagWar, so their settled occupations must not
 * depend on FlagWar.enabled or the optional occupied-town control list.
 * Chunk occupation takes precedence until a core capture occupies the whole
 * territory.
 */
internal fun warzoneOccupier(territory: Territory, territoryChunk: TerritoryChunk): Town? = if (Warzone.isActive(territory)) territoryChunk.occupier ?: territory.occupier else null

// bypass permissions and allow all interaction in
// captured chunks/territories during wartime
private fun hasWarPermissions(resident: Resident, territory: Territory, territoryChunk: TerritoryChunk): Boolean {
    if (FlagWar.enabled || territoryChunk.attacker !== null || FlagWar.isColonized(territoryChunk.coord)) {
        val residentTown = resident.town
        val territoryTown = territory.town

        if (residentTown !== null) {
            // extended permissions for allies
            if (Nodes.config.warPermissions) {
                val residentNation = residentTown.nation

                val territoryOccupierNation = territory.occupier?.nation
                val territoryTownNation = territoryTown?.nation
                val chunkOccupierNation = territoryChunk.occupier?.nation
                val chunkAttackerNation = territoryChunk.attacker?.nation

                if (territory.occupier === residentTown ||
                    (residentNation !== null && territoryOccupierNation !== null && residentNation.allies.contains(territoryOccupierNation)) ||
                    territoryChunk.occupier === residentTown ||
                    territoryChunk.attacker === residentTown ||
                    (residentNation !== null && territoryTownNation !== null && residentNation.allies.contains(territoryTownNation)) ||
                    (residentNation !== null && chunkOccupierNation !== null && residentNation.allies.contains(chunkOccupierNation)) ||
                    (residentNation !== null && chunkAttackerNation !== null && residentNation.allies.contains(chunkAttackerNation))
                ) {
                    return true
                }

                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            }
            // only let town/nation by default
            else {
                if (territory.occupier === residentTown || territoryChunk.occupier === residentTown || territoryChunk.attacker === residentTown) {
                    return true
                }

                val residentNation = residentTown.nation
                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            }
        }
    }

    return false
}
