package net.aechronis.nodes.objects

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Decorative flags not tied to any nation (event/meme flags etc.), bundled as
 * `flag-textures/other-flags.json`: `[{"id":"...", "name":"...", "url":"..."}, ...]`. Add one by
 * editing that file and redeploying; there's no in-game command for it.
 */
internal object OtherFlags {
    data class Flag(val id: UUID, val name: String, val url: String)

    val all: List<Flag> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/flag-textures/other-flags.json")) {
            "Missing bundled resource: /flag-textures/other-flags.json"
        }.use { it.readBytes().decodeToString() }
        Json.parseToJsonElement(text).jsonArray.map { entry ->
            val obj = entry.jsonObject
            val id = requireNotNull(obj["id"]?.jsonPrimitive?.contentOrNull) { "other-flags.json entry missing \"id\"" }
            val name = requireNotNull(obj["name"]?.jsonPrimitive?.contentOrNull) { "other-flags.json entry missing \"name\"" }
            val url = requireNotNull(obj["url"]?.jsonPrimitive?.contentOrNull) { "other-flags.json entry missing \"url\"" }
            // Derived deterministically from the string id so the same entry always maps to the
            // same resource-pack/texture-cache key across restarts, without persisting a UUID.
            Flag(UUID.nameUUIDFromBytes(id.toByteArray()), name, url)
        }
    }
}
