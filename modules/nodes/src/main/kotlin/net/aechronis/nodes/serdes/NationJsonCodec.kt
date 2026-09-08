package net.aechronis.nodes.serdes

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.aechronis.nodes.objects.Nation

internal object NationJsonCodec {
    fun encode(snapshot: Nation.NationSaveState): String = buildJsonObject {
        put("uuid", snapshot.uuid.toString())
        put("capital", snapshot.capital)
        putJsonArray("color") {
            add(snapshot.color.r)
            add(snapshot.color.g)
            add(snapshot.color.b)
        }
        snapshot.rallyCap?.let { put("rallyCap", it) }
        putJsonArray("towns") { snapshot.towns.forEach { add(it) } }
        putJsonArray("allies") { snapshot.allies.forEach { add(it) } }
        putJsonArray("enemies") { snapshot.enemies.forEach { add(it) } }
    }.toString()
}
