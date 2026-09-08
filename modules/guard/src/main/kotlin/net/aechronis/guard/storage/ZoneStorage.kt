package net.aechronis.guard.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.aechronis.guard.flags.BooleanFlagValue
import net.aechronis.guard.flags.DecimalFlagValue
import net.aechronis.guard.flags.FlagName
import net.aechronis.guard.flags.FlagValue
import net.aechronis.guard.flags.IntegerFlagValue
import net.aechronis.guard.flags.NumberListFlagValue
import net.aechronis.guard.flags.StringFlagValue
import net.aechronis.guard.flags.StringListFlagValue
import net.aechronis.guard.objects.Zone
import net.aechronis.guard.objects.ZoneBounds
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ZoneStorage {
    @Serializable
    private data class SavedZones(
        val zones: List<SavedZone>,
    )

    @Serializable
    private data class SavedZone(
        val name: String,
        val priority: Int = 0,
        val bounds: ZoneBounds,
        val flags: Map<String, JsonElement> = emptyMap(),
    )

    @Serializable
    private data class SavedFlag(
        val type: String,
        val value: JsonElement,
    )

    fun load(path: Path): List<Zone> {
        if (!Files.exists(path)) return emptyList()
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val zones = root["zones"]?.jsonArray ?: return emptyList()
        return zones.mapIndexedNotNull { index, element ->
            runCatching { readZone(Json.decodeFromString<SavedZone>(element.toString())) }
                .onFailure { error ->
                    val name = runCatching { element.jsonObject["name"]?.jsonPrimitive?.content }.getOrNull() ?: "#${index + 1}"
                    System.err.println("Guard skipped invalid zone $name in $path: ${error.message ?: error}")
                }.getOrNull()
        }
    }

    fun save(
        path: Path,
        zones: Collection<Zone>,
    ) {
        val parent = path.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val saved =
            SavedZones(
                zones.map { zone ->
                    SavedZone(
                        zone.name,
                        zone.priority,
                        zone.bounds,
                        zone.flags.mapKeys { (name, _) -> name.id }.mapValues { (_, value) ->
                            Json.encodeToJsonElement(writeValue(value))
                        },
                    )
                },
            )

        val temporary = Files.createTempFile(parent, "${path.fileName}-", ".tmp")
        try {
            Files.writeString(temporary, Json.encodeToString(saved))
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readZone(saved: SavedZone): Zone =
        Zone(
            name = saved.name,
            bounds = saved.bounds,
            priority = saved.priority,
            flags =
                saved.flags
                    .mapNotNull { (id, value) ->
                        FlagName.fromId(id)?.let { it to readValue(Json.decodeFromJsonElement<SavedFlag>(value)) }
                    }.toMap(),
        )

    private fun readValue(saved: SavedFlag): FlagValue =
        when (saved.type) {
            "boolean" -> BooleanFlagValue(Json.decodeFromJsonElement<Boolean>(saved.value))
            "string" -> StringFlagValue(Json.decodeFromJsonElement<String>(saved.value))
            "string-array" -> StringListFlagValue(Json.decodeFromJsonElement<List<String>>(saved.value))
            "number-array" -> NumberListFlagValue(Json.decodeFromJsonElement<List<Double>>(saved.value))
            "integer" -> IntegerFlagValue(Json.decodeFromJsonElement<Long>(saved.value))
            "decimal" -> DecimalFlagValue(Json.decodeFromJsonElement<Double>(saved.value))
            else -> error("Unknown flag value type: ${saved.type}")
        }

    private fun writeValue(value: FlagValue): SavedFlag =
        when (value) {
            is BooleanFlagValue -> SavedFlag("boolean", JsonPrimitive(value.value))
            is StringFlagValue -> SavedFlag("string", JsonPrimitive(value.value))
            is StringListFlagValue -> SavedFlag("string-array", JsonArray(value.value.map(::JsonPrimitive)))
            is NumberListFlagValue -> SavedFlag("number-array", JsonArray(value.value.map(::JsonPrimitive)))
            is IntegerFlagValue -> SavedFlag("integer", JsonPrimitive(value.value))
            is DecimalFlagValue -> SavedFlag("decimal", JsonPrimitive(value.value))
            else -> error("Unsupported flag value: ${value::class.qualifiedName}")
        }
}
