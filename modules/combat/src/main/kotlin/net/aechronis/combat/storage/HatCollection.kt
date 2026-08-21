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

        playerCollections[uuid] = ConcurrentHashMap.newKeySet<String>().apply { addAll(parseHats(Files.readString(file))) }
    }

    fun save(uuid: UUID) {
        val collection = playerCollections[uuid] ?: return
        val file = getPlayerFile(uuid)
        Files.createDirectories(file.parent)
        Files.writeString(file, toJson(collection))
    }

    fun unload(uuid: UUID) {
        save(uuid)
        playerCollections.remove(uuid)
    }

    fun owns(
        uuid: UUID,
        hat: Hat,
    ): Boolean = playerCollections[uuid]?.contains(hat.name) == true

    fun hats(uuid: UUID): List<Hat> =
        playerCollections[uuid]
            .orEmpty()
            .mapNotNull { Item.getFromName(it) as? Hat }
            .sortedBy { it.name }

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
        if (collection.remove(hat.name)) save(uuid)
    }

    private fun parseHats(json: String): List<String> {
        val hatsMatch = Regex(""""hats"\s*:\s*\[([^\]]*)]""").find(json) ?: return emptyList()
        return hatsMatch
            .groupValues[1]
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }

    private fun toJson(collection: Set<String>): String {
        val hats = collection.sorted().joinToString(",", "[", "]") { "\"$it\"" }
        return """{"hats":$hats}"""
    }
}
