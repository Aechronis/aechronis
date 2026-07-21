package net.aechronis.nodes.objects

import java.util.Locale
import java.util.UUID

enum class WaypointSharing(val id: String) {
    PRIVATE("private"),
    TOWN("town"),
    NATION("nation"),
    ALLY("ally"),
    ;

    companion object {
        fun fromId(id: String): WaypointSharing = entries.firstOrNull { it.id == id.lowercase(Locale.ROOT) }
            ?: throw IllegalArgumentException("Unknown waypoint sharing option: $id")
    }
}

/** Immutable location displayed on a resident's minimap and in the world. */
data class Waypoint(
    val name: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val sharing: WaypointSharing = WaypointSharing.PRIVATE,
    val sharedGroupId: UUID? = null,
) {
    init {
        require(name == normalizeName(name)) { "Waypoint name must not have surrounding whitespace" }
        require((sharing == WaypointSharing.PRIVATE) == (sharedGroupId == null)) { "Shared waypoints require a group" }
    }

    val key: String get() = key(name)
    val chunkX: Int get() = Math.floorDiv(x, 16)
    val chunkZ: Int get() = Math.floorDiv(z, 16)

    internal fun isSharedWith(resident: Resident): Boolean = when (sharing) {
        WaypointSharing.PRIVATE -> false
        WaypointSharing.TOWN -> resident.town?.uuid == sharedGroupId
        WaypointSharing.NATION -> resident.town?.nation?.uuid == sharedGroupId
        WaypointSharing.ALLY -> {
            val nation = resident.town?.nation
            nation?.uuid == sharedGroupId || nation?.allies?.any { ally -> ally.uuid == sharedGroupId } == true
        }
    }

    companion object {
        const val DEATH_NAME = "Death"
        const val MAX_NAME_LENGTH = 32

        fun normalizeName(input: String): String {
            val name = input.trim()
            require(name.isNotEmpty()) { "Waypoint name cannot be empty" }
            require(name.length <= MAX_NAME_LENGTH) { "Waypoint name must be $MAX_NAME_LENGTH characters or less" }
            require(name.none(Char::isISOControl)) { "Waypoint name cannot contain control characters" }
            return name
        }

        fun key(input: String): String = normalizeName(input).lowercase(Locale.ROOT)
    }
}
