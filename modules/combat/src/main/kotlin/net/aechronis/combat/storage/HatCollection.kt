package net.aechronis.combat.storage

import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Item
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object HatCollection {
    private var dataDirectory: Path = Path.of("combat", "hats")
    private val playerCollections = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val playerEquippedHat = ConcurrentHashMap<UUID, String>()
    private val playerSelectedHat = ConcurrentHashMap<UUID, String>()

    fun initialize() {
        Files.createDirectories(dataDirectory)
    }

    private fun getPlayerFile(uuid: UUID): Path = dataDirectory.resolve("$uuid.json")

    fun load(uuid: UUID) {
        val file = getPlayerFile(uuid)
        if (!Files.exists(file)) {
            playerCollections[uuid] = ConcurrentHashMap.newKeySet()
            return
        }

        val content = Files.readString(file)
        val data = parseJson(content)
        playerCollections[uuid] = ConcurrentHashMap.newKeySet<String>().apply { addAll(data.hats) }
        if (data.equipped != null) {
            playerEquippedHat[uuid] = data.equipped
        }
    }

    fun save(uuid: UUID) {
        val collection = playerCollections[uuid] ?: return
        val equipped = playerEquippedHat[uuid]
        val file = getPlayerFile(uuid)
        Files.createDirectories(file.parent)
        Files.writeString(file, toJson(collection, equipped))
    }

    fun unload(uuid: UUID) {
        save(uuid)
        playerCollections.remove(uuid)
        playerEquippedHat.remove(uuid)
        playerSelectedHat.remove(uuid)
    }

    fun give(
        uuid: UUID,
        hat: Hat,
    ) {
        val collection = playerCollections.getOrPut(uuid) { ConcurrentHashMap.newKeySet() }
        collection.add(hat.name)
        save(uuid)
    }

    fun remove(
        uuid: UUID,
        hat: Hat,
    ) {
        val collection = playerCollections[uuid] ?: return
        val removed = collection.remove(hat.name)
        if (removed) {
            // unequip if the removed hat was equipped
            if (playerEquippedHat[uuid] == hat.name) {
                playerEquippedHat.remove(uuid)
            }
            if (playerSelectedHat[uuid] == hat.name) {
                playerSelectedHat.remove(uuid)
            }
            save(uuid)
        }
    }

    fun equip(
        uuid: UUID,
        hat: Hat?,
    ) {
        if (hat == null) {
            playerEquippedHat.remove(uuid)
        } else {
            playerEquippedHat[uuid] = hat.name
        }

        save(uuid)
    }

    fun getEquippedHat(uuid: UUID): Hat? {
        val hatName = playerEquippedHat[uuid] ?: return null
        val item = Item.getFromName(hatName)
        return item as? Hat
    }

    fun getSelectedHat(uuid: UUID): Hat? {
        val hat = playerSelectedHat[uuid] ?: return null
        return Item.getFromName(hat) as? Hat
    }

    fun cycleSelectedHat(
        uuid: UUID,
        forward: Boolean,
    ) {
        val collection = playerCollections[uuid] ?: return
        if (collection.isEmpty()) return

        val hatsList = collection.toList()
        val currentSelected = playerSelectedHat[uuid]

        val nextIndex =
            if (forward) {
                val currentIndex = if (currentSelected != null) hatsList.indexOf(currentSelected) else -1
                (currentIndex + 1) % hatsList.size
            } else {
                val currentIndex = if (currentSelected != null) hatsList.indexOf(currentSelected) else 0
                if (currentIndex <= 0) hatsList.size - 1 else currentIndex - 1
            }

        playerSelectedHat[uuid] = hatsList[nextIndex]
    }

    fun resetSelectedHat(uuid: UUID) {
        playerSelectedHat.remove(uuid)
    }

    private data class PlayerData(
        val hats: List<String>,
        val equipped: String?,
    )

    private fun parseJson(json: String): PlayerData {
        val trimmed = json.trim()
        // {"hats":[...],"equipped":"..."}
        var hats = emptyList<String>()
        var equipped: String? = null

        // hats array
        val hatsMatch = Regex(""""hats"\s*:\s*\[([^\]]*)]""").find(trimmed)
        if (hatsMatch != null) {
            hats = parseHatsArray("[${hatsMatch.groupValues[1]}]")
        }

        // equipped
        val equippedMatch = Regex(""""equipped"\s*:\s*"([^"]*)"""").find(trimmed)
        if (equippedMatch != null) {
            equipped = equippedMatch.groupValues[1].ifEmpty { null }
        }

        return PlayerData(hats, equipped)
    }

    private fun parseHatsArray(json: String): List<String> {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()

        return trimmed
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }

    private fun toJson(
        collection: Set<String>,
        equipped: String?,
    ): String {
        val hatsJson =
            if (collection.isEmpty()) {
                "[]"
            } else {
                collection.joinToString(",", "[", "]") { "\"$it\"" }
            }

        val equippedJson = if (equipped != null) "\"$equipped\"" else "null"

        return """{"hats":$hatsJson,"equipped":$equippedJson}"""
    }
}
