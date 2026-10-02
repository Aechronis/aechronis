package net.aechronis.nodes.objects

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.aechronis.nodes.Nodes
import java.nio.file.Files
import java.util.UUID

/**
 * Decorative flags not tied to any nation (event/meme flags etc.), read from `nodes/other-flags.json`
 * next to towns.json: `[{"id":"...", "name":"...", "url":"..."}, ...]`. The file is optional.
 * Changes are picked up the next time the flag pack is rebuilt (`/modules reload nodes` or restart).
 */
internal object OtherFlags {
    data class Flag(val id: UUID, val name: String, val url: String)

    val all: List<Flag>
        get() {
            val path = Nodes.config.pathOtherFlags
            if (Files.notExists(path)) return emptyList()
            return try {
                Json.parseToJsonElement(Files.readString(path)).jsonArray.mapNotNull { entry ->
                    val obj = entry.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    if (id == null || name == null || url == null) {
                        System.err.println("[OtherFlags] Skipping entry without id/name/url in $path")
                        return@mapNotNull null
                    }
                    // Derived from the string id so the same entry keeps the same pack/texture-cache key across restarts.
                    Flag(UUID.nameUUIDFromBytes(id.toByteArray()), name, url)
                }
            } catch (exception: Exception) {
                System.err.println("[OtherFlags] Could not read $path: ${exception.javaClass.simpleName}: ${exception.message}")
                emptyList()
            }
        }
}
