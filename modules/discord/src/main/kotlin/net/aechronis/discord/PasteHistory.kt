package net.aechronis.discord

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import net.aechronis.server.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Completed pastes. `chunks` is the paid-for footprint, packed as (cx shl 32) or cz; a player pastes there again for free. */
internal class PasteHistory(
    private val path: Path,
) {
    class Entry(
        val id: String,
        val player: UUID,
        val x: Int,
        val y: Int,
        val z: Int,
        val town: String,
        val chunks: Set<Long>,
        val time: Long,
    )

    private var entries: List<Entry> = load()

    /** Every chunk this player has paid for across all their pastes. */
    @Synchronized
    fun paid(player: UUID): Set<Long> = entries.filter { it.player == player }.flatMapTo(HashSet()) { it.chunks }

    @Synchronized
    fun add(entry: Entry) = save(entries + entry)

    private fun load(): List<Entry> {
        if (!Files.exists(path)) return emptyList()
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        require(root["version"]?.jsonPrimitive?.content == "1") { "Unsupported paste history file" }
        return root.getValue("pastes").jsonArray.map {
            val e = it.jsonObject
            Entry(
                e.getValue("id").jsonPrimitive.content,
                UUID.fromString(e.getValue("player").jsonPrimitive.content),
                e
                    .getValue("x")
                    .jsonPrimitive.content
                    .toInt(),
                e
                    .getValue("y")
                    .jsonPrimitive.content
                    .toInt(),
                e
                    .getValue("z")
                    .jsonPrimitive.content
                    .toInt(),
                e.getValue("town").jsonPrimitive.content,
                e
                    .getValue("chunks")
                    .jsonArray
                    .map { c -> c.jsonPrimitive.long }
                    .toSet(),
                e.getValue("time").jsonPrimitive.long,
            )
        }
    }

    private fun save(updated: List<Entry>) {
        val data =
            buildJsonObject {
                put("version", 1)
                put(
                    "pastes",
                    JsonArray(
                        updated.map { e ->
                            buildJsonObject {
                                put("id", e.id)
                                put("player", e.player.toString())
                                put("x", e.x)
                                put("y", e.y)
                                put("z", e.z)
                                put("town", e.town)
                                put("chunks", JsonArray(e.chunks.map(::JsonPrimitive)))
                                put("time", e.time)
                            }
                        },
                    ),
                )
            }
        val destination = path.toAbsolutePath()
        Files.createDirectories(destination.parent)
        AtomicFiles.withTemporaryFile(destination) { temporary ->
            Files.writeString(temporary, data.toString())
            AtomicFiles.replace(temporary, destination, requireAtomic = true)
            entries = updated
        }
    }
}
