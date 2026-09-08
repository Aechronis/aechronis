package net.aechronis.nodes.serdes

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.aechronis.nodes.objects.Resident

internal object ResidentJsonCodec {
    fun encode(snapshot: Resident.ResidentSaveState): String = buildJsonObject {
        put("name", snapshot.name)
        put("town", snapshot.town)
        put("nation", snapshot.nation)
        put("trust", snapshot.trusted)
        put("minimap", snapshot.minimapEnabled)
        put("minimapPosition", snapshot.minimapPosition.id)
        put("minimapShift", snapshot.minimapShiftEnabled)
        put("minimapNorthLocked", snapshot.minimapNorthLocked)
        put("townJoinLockedUntil", snapshot.townJoinLockedUntil)
        putJsonArray("waypoints") {
            snapshot.waypoints.forEach { waypoint ->
                add(
                    buildJsonObject {
                        put("name", waypoint.name)
                        put("x", waypoint.x)
                        put("y", waypoint.y)
                        put("z", waypoint.z)
                        put("sharing", waypoint.sharing.id)
                        put("sharedGroup", waypoint.sharedGroupId?.toString())
                    },
                )
            }
        }
        putJsonObject("waypointVisibility") { snapshot.waypointVisibility.forEach { (key, visible) -> put(key, visible) } }
    }.toString()
}
