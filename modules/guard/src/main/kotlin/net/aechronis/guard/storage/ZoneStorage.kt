package net.aechronis.guard.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import java.util.UUID

class ZoneStorage {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(path: Path): List<Zone> {
        if (!Files.exists(path)) return emptyList()
        val root = Files.newBufferedReader(path).use { JsonParser.parseReader(it) }
        val zones = root.asJsonObject.getAsJsonArray("zones") ?: return emptyList()
        return zones.mapIndexedNotNull { index, element ->
            runCatching { readZone(element.asJsonObject) }
                .onFailure { error ->
                    val name = runCatching { element.asJsonObject.get("name")?.asString }.getOrNull() ?: "#${index + 1}"
                    System.err.println("Guard skipped invalid zone $name in $path: ${error.message ?: error}")
                }.getOrNull()
        }
    }

    fun migrateInstanceIds(
        zones: Collection<Zone>,
        migration: (UUID) -> UUID,
    ): List<Zone> = zones.map { zone -> zone.copy(instanceId = migration(zone.instanceId)) }

    fun save(
        path: Path,
        zones: Collection<Zone>,
    ) {
        val parent = path.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val root = JsonObject()
        root.add("zones", JsonArray().also { array -> zones.forEach { array.add(writeZone(it)) } })

        val temporary = Files.createTempFile(parent, "${path.fileName}-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { gson.toJson(root, it) }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readZone(json: JsonObject): Zone {
        val bounds = json.getAsJsonObject("bounds")
        val flags =
            json.getAsJsonObject("flags")?.entrySet()?.associateNotNull { (id, value) ->
                FlagName.fromId(id)?.let { it to readValue(value.asJsonObject) }
            } ?: emptyMap()
        return Zone(
            name = json.get("name").asString,
            instanceId = UUID.fromString(json.get("instanceId").asString),
            bounds =
                ZoneBounds(
                    bounds.get("minX").asInt,
                    bounds.get("minY").asInt,
                    bounds.get("minZ").asInt,
                    bounds.get("maxX").asInt,
                    bounds.get("maxY").asInt,
                    bounds.get("maxZ").asInt,
                ),
            priority = json.get("priority")?.asInt ?: 0,
            flags = flags,
        )
    }

    private fun writeZone(zone: Zone): JsonObject =
        JsonObject().apply {
            addProperty("name", zone.name)
            addProperty("instanceId", zone.instanceId.toString())
            addProperty("priority", zone.priority)
            add(
                "bounds",
                JsonObject().apply {
                    addProperty("minX", zone.bounds.minX)
                    addProperty("minY", zone.bounds.minY)
                    addProperty("minZ", zone.bounds.minZ)
                    addProperty("maxX", zone.bounds.maxX)
                    addProperty("maxY", zone.bounds.maxY)
                    addProperty("maxZ", zone.bounds.maxZ)
                },
            )
            add("flags", JsonObject().also { flags -> zone.flags.forEach { (name, value) -> flags.add(name.id, writeValue(value)) } })
        }

    private fun readValue(json: JsonObject): FlagValue =
        when (json.get("type").asString) {
            "boolean" -> BooleanFlagValue(json.get("value").asBoolean)
            "string" -> StringFlagValue(json.get("value").asString)
            "string-array" -> StringListFlagValue(json.getAsJsonArray("value").map { it.asString })
            "number-array" -> NumberListFlagValue(json.getAsJsonArray("value").map { it.asDouble })
            "integer" -> IntegerFlagValue(json.get("value").asLong)
            "decimal" -> DecimalFlagValue(json.get("value").asDouble)
            else -> error("Unknown flag value type: ${json.get("type").asString}")
        }

    private fun writeValue(value: FlagValue): JsonObject =
        JsonObject().apply {
            when (value) {
                is BooleanFlagValue -> {
                    addProperty("type", "boolean")
                    addProperty("value", value.value)
                }
                is StringFlagValue -> {
                    addProperty("type", "string")
                    addProperty("value", value.value)
                }
                is StringListFlagValue -> {
                    addProperty("type", "string-array")
                    add("value", JsonArray().also { value.value.forEach(it::add) })
                }
                is NumberListFlagValue -> {
                    addProperty("type", "number-array")
                    add("value", JsonArray().also { value.value.forEach(it::add) })
                }
                is IntegerFlagValue -> {
                    addProperty("type", "integer")
                    addProperty("value", value.value)
                }
                is DecimalFlagValue -> {
                    addProperty("type", "decimal")
                    addProperty("value", value.value)
                }
                else -> error("Unsupported flag value: ${value::class.qualifiedName}")
            }
        }

    private inline fun <T, R> Iterable<T>.associateNotNull(transform: (T) -> Pair<R, FlagValue>?): Map<R, FlagValue> =
        mapNotNull(transform).toMap()
}
