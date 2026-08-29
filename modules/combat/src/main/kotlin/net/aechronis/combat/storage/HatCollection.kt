package net.aechronis.combat.storage

import com.google.gson.Gson
import com.google.gson.JsonParser
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.Item
import net.minestom.server.MinecraftServer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object HatCollection {
    private val store = HatCollectionStore(Path.of("combat", "hats"))

    fun initialize() {
        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers.map { it.uuid }
        store.initialize(onlinePlayers)
    }

    fun load(uuid: UUID) = store.load(uuid)

    fun save(uuid: UUID) = store.save(uuid)

    fun unload(uuid: UUID) = store.unload(uuid)

    fun shutdown() = store.shutdown()

    fun owns(
        uuid: UUID,
        hat: Hat,
    ): Boolean = store.owns(uuid, hat)

    fun hats(uuid: UUID): List<Hat> = store.hats(uuid)

    fun give(
        uuid: UUID,
        hat: Hat,
    ) = store.give(uuid, hat)

    fun remove(
        uuid: UUID,
        hat: Hat,
    ) = store.remove(uuid, hat)
}

internal class HatCollectionStore(
    private val dataDirectory: Path,
) {
    private val gson = Gson()
    private val playerCollections = ConcurrentHashMap<UUID, MutableSet<String>>()

    fun initialize(onlinePlayerUuids: Iterable<UUID>) {
        Files.createDirectories(dataDirectory)
        onlinePlayerUuids.forEach(::load)
    }

    private fun getPlayerFile(uuid: UUID): Path = dataDirectory.resolve("$uuid.json")

    fun load(uuid: UUID) {
        getOrLoad(uuid)
    }

    fun save(uuid: UUID) {
        val collection = playerCollections[uuid] ?: return
        val file = getPlayerFile(uuid)
        writeAtomically(file, gson.toJson(mapOf("hats" to collection.sorted())))
    }

    fun unload(uuid: UUID) {
        save(uuid)
        playerCollections.remove(uuid)
    }

    fun shutdown() {
        val failures = ArrayList<Throwable>()
        for (uuid in playerCollections.keys.toList()) {
            try {
                save(uuid)
                playerCollections.remove(uuid)
            } catch (exception: Throwable) {
                failures.add(exception)
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Failed to save ${failures.size} hat collection(s)").apply {
                failures.forEach(::addSuppressed)
            }
        }
    }

    fun owns(
        uuid: UUID,
        hat: Hat,
    ): Boolean = getOrLoad(uuid).contains(hat.name)

    fun hats(uuid: UUID): List<Hat> =
        getOrLoad(uuid)
            .mapNotNull { Item.getFromName(it) as? Hat }
            .sortedBy { it.name }

    fun give(
        uuid: UUID,
        hat: Hat,
    ) {
        val collection = getOrLoad(uuid)
        collection.add(hat.name)
        save(uuid)
    }

    fun remove(
        uuid: UUID,
        hat: Hat,
    ) {
        val collection = getOrLoad(uuid)
        if (collection.remove(hat.name)) save(uuid)
    }

    private fun getOrLoad(uuid: UUID): MutableSet<String> =
        playerCollections.computeIfAbsent(uuid) {
            val file = getPlayerFile(uuid)
            ConcurrentHashMap.newKeySet<String>().apply {
                if (Files.exists(file)) addAll(parseHats(Files.readString(file)))
            }
        }

    private fun parseHats(json: String): List<String> {
        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "Hat collection must be a JSON object" }
        val hats = root.asJsonObject.get("hats")
        require(hats != null && hats.isJsonArray) { "Hat collection must contain a hats array" }
        return hats.asJsonArray.map { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                "Hat collection entries must be strings"
            }
            element.asString.also { name -> require(name.isNotBlank()) { "Hat names must not be blank" } }
        }
    }

    private fun writeAtomically(
        target: Path,
        contents: String,
    ) {
        val parent = target.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${target.fileName}-", ".tmp")
        var failure: Throwable? = null
        try {
            Files.writeString(temporary, contents)
            preservePermissions(target, temporary)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            runCatching { Files.deleteIfExists(temporary) }.exceptionOrNull()?.let { cleanupError ->
                failure?.addSuppressed(cleanupError) ?: throw cleanupError
            }
        }
    }

    private fun preservePermissions(
        source: Path,
        target: Path,
    ) {
        if (!Files.exists(source)) return
        val sourceAttributes = Files.getFileAttributeView(source, PosixFileAttributeView::class.java) ?: return
        val targetAttributes = Files.getFileAttributeView(target, PosixFileAttributeView::class.java) ?: return
        targetAttributes.setPermissions(sourceAttributes.readAttributes().permissions())
    }
}
