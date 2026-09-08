package net.aechronis.nodes.serdes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.aechronis.nodes.objects.Plot

internal object PlotJsonCodec {
    fun encode(snapshot: Plot.PlotSaveState): String = toJsonElement(snapshot).toString()

    fun toJsonElement(snapshot: Plot.PlotSaveState): JsonObject = buildJsonObject {
        put("name", snapshot.name)
        putJsonArray("min") {
            add(snapshot.minX)
            add(snapshot.minY)
            add(snapshot.minZ)
        }
        putJsonArray("max") {
            add(snapshot.maxX)
            add(snapshot.maxY)
            add(snapshot.maxZ)
        }
        putJsonObject("permissions") {
            snapshot.groupPermissions.forEach { (group, permissions) ->
                putJsonObject(group.toString()) { permissions.forEach { (permission, allowed) -> put(permission.toString(), allowed) } }
            }
        }
        putJsonObject("players") {
            snapshot.playerPermissions.forEach { (player, permissions) ->
                putJsonObject(player.toString()) { permissions.forEach { (permission, allowed) -> put(permission.toString(), allowed) } }
            }
        }
    }
}
