package net.aechronis.nodes

import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.objects.WaypointSharing

private const val WAYPOINT_ICON_CODEPOINT = 0xE002
private const val DEATH_WAYPOINT_ICON_CODEPOINT = 0xE003
private const val LARGE_SCALE_WAYPOINT_ICON_CODEPOINT = 0xE004
private const val LARGE_SCALE_DEATH_WAYPOINT_ICON_CODEPOINT = 0xE005
private const val TOWN_WAYPOINT_ICON_CODEPOINT = 0xE006
private const val NATION_WAYPOINT_ICON_CODEPOINT = 0xE007
private const val LARGE_SCALE_TOWN_WAYPOINT_ICON_CODEPOINT = 0xE008
private const val LARGE_SCALE_NATION_WAYPOINT_ICON_CODEPOINT = 0xE009
private const val ALLY_WAYPOINT_ICON_CODEPOINT = 0xE00A
private const val LARGE_SCALE_ALLY_WAYPOINT_ICON_CODEPOINT = 0xE00B
private const val CORE_ICON_CODEPOINT = 0xECF0
internal const val PLAYER_ICON_CODEPOINT = 0xECF1

internal object MinimapIcons {
    fun waypointIconCodepoint(scale: Int, sharing: WaypointSharing): Int {
        val largeScale = when (scale) {
            4 -> false
            12 -> true
            else -> throw IllegalArgumentException("Unsupported minimap scale: $scale")
        }
        return when (sharing) {
            WaypointSharing.PRIVATE -> if (largeScale) LARGE_SCALE_WAYPOINT_ICON_CODEPOINT else WAYPOINT_ICON_CODEPOINT
            WaypointSharing.TOWN -> if (largeScale) LARGE_SCALE_TOWN_WAYPOINT_ICON_CODEPOINT else TOWN_WAYPOINT_ICON_CODEPOINT
            WaypointSharing.NATION -> if (largeScale) LARGE_SCALE_NATION_WAYPOINT_ICON_CODEPOINT else NATION_WAYPOINT_ICON_CODEPOINT
            WaypointSharing.ALLY -> if (largeScale) LARGE_SCALE_ALLY_WAYPOINT_ICON_CODEPOINT else ALLY_WAYPOINT_ICON_CODEPOINT
        }
    }

    fun deathWaypointIconCodepoint(scale: Int): Int = when (scale) {
        4 -> DEATH_WAYPOINT_ICON_CODEPOINT
        12 -> LARGE_SCALE_DEATH_WAYPOINT_ICON_CODEPOINT
        else -> throw IllegalArgumentException("Unsupported minimap scale: $scale")
    }

    fun attackIconCodepoint(relationship: DiplomaticRelationship): Int = when (relationship) {
        DiplomaticRelationship.TOWN -> 0xE486
        DiplomaticRelationship.NATION -> 0xE41E
        DiplomaticRelationship.ALLY -> 0xE4DE
        DiplomaticRelationship.NEUTRAL -> 0xE47A
        DiplomaticRelationship.ENEMY -> 0xE412
    }

    fun territoryIconCodepoint(relationship: DiplomaticRelationship?): Int = when (relationship) {
        DiplomaticRelationship.TOWN -> 0xEC86
        DiplomaticRelationship.NATION -> 0xEC1E
        DiplomaticRelationship.ALLY -> 0xECDE
        DiplomaticRelationship.NEUTRAL -> 0xEC7A
        DiplomaticRelationship.ENEMY -> 0xEC12
        null -> 0xEC5A
    }

    fun coreIconCodepoint(): Int = CORE_ICON_CODEPOINT
}
