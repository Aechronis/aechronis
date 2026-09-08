package net.aechronis.combat.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.HatInstance
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

object HatCollection {
    private val store = HatCollectionStore(Path.of("combat", "hats"))

    fun initialize() = store.initialize()

    fun shutdown() = store.shutdown()

    fun owns(
        uuid: UUID,
        hat: HatInstance,
    ): Boolean = store.owns(uuid, hat)

    fun hats(uuid: UUID): List<HatInstance> = store.hats(uuid)

    fun equipped(uuid: UUID): HatInstance? = store.equipped(uuid)

    fun equip(
        uuid: UUID,
        hat: HatInstance?,
    ) = store.equip(uuid, hat)

    fun give(
        uuid: UUID,
        hat: Hat,
    ): HatInstance = store.give(uuid, hat)

    fun remove(
        uuid: UUID,
        number: Long,
    ): HatInstance? = store.remove(uuid, number)
}

/** Ownership and the global sequence commit together, so a failed write cannot issue a duplicate number. */
internal class HatCollectionStore(
    private val dataDirectory: Path,
) {
    @Serializable
    private data class SavedHats(
        val hats: List<HatInstance> = emptyList(),
        val equipped: Long? = null,
    )

    @Serializable
    private data class Registry(
        val version: Int = 1,
        val nextNumber: Long = 1,
        val players: Map<String, SavedHats> = emptyMap(),
    )

    private val json = Json { encodeDefaults = true }
    private val registryFile = dataDirectory.resolve("registry.json")

    @Volatile private var registry: Registry? = null

    @Synchronized
    fun initialize() {
        if (registry != null) return
        Files.createDirectories(dataDirectory)
        val saved =
            if (Files.exists(registryFile)) {
                json.decodeFromString<Registry>(Files.readString(registryFile))
            } else {
                Registry()
            }
        validate(saved)
        if (!Files.exists(registryFile)) writeAtomically(registryFile, json.encodeToString(saved))
        registry = saved
    }

    @Synchronized
    fun shutdown() {
        // Every mutation is already committed before being published in memory.
        registry = null
    }

    private fun current(): Registry = checkNotNull(registry) { "Hat registry has not been initialized" }

    fun owns(
        uuid: UUID,
        hat: HatInstance,
    ): Boolean = current().players[uuid.toString()]?.hats?.contains(hat) == true

    fun hats(uuid: UUID): List<HatInstance> =
        current()
            .players[uuid.toString()]
            ?.hats
            .orEmpty()
            .filter { it.definition != null }
            .sortedBy { it.number }

    // Packet rendering only reads an immutable snapshot; it never performs disk I/O.
    fun equipped(uuid: UUID): HatInstance? {
        val collection = registry?.players?.get(uuid.toString()) ?: return null
        return collection.hats.firstOrNull { it.number == collection.equipped && it.definition != null }
    }

    @Synchronized
    fun equip(
        uuid: UUID,
        hat: HatInstance?,
    ) {
        val saved = current()
        val owner = uuid.toString()
        val collection = saved.players[owner] ?: SavedHats()
        require(hat == null || (hat in collection.hats && hat.definition != null)) { "You do not own this hat" }
        val updated = collection.copy(equipped = hat?.number)
        if (updated == collection) return
        commit(saved.copy(players = saved.players + (owner to updated)))
    }

    @Synchronized
    fun give(
        uuid: UUID,
        hat: Hat,
    ): HatInstance {
        val saved = current()
        check(saved.nextNumber < Long.MAX_VALUE) { "Hat numbers are exhausted" }
        val owner = uuid.toString()
        val collection = saved.players[owner] ?: SavedHats()
        val instance = HatInstance(saved.nextNumber, hat.name)
        commit(
            saved.copy(
                nextNumber = saved.nextNumber + 1,
                players = saved.players + (owner to collection.copy(hats = collection.hats + instance)),
            ),
        )
        return instance
    }

    @Synchronized
    fun remove(
        uuid: UUID,
        number: Long,
    ): HatInstance? {
        val saved = current()
        val owner = uuid.toString()
        val collection = saved.players[owner] ?: return null
        val hat = collection.hats.firstOrNull { it.number == number } ?: return null
        val updated =
            collection.copy(
                hats = collection.hats - hat,
                equipped = collection.equipped.takeUnless { it == number },
            )
        commit(saved.copy(players = saved.players + (owner to updated)))
        return hat
    }

    private fun commit(saved: Registry) {
        writeAtomically(registryFile, json.encodeToString(saved))
        registry = saved
    }

    private fun validate(saved: Registry) {
        require(saved.version == 1) { "Unsupported hat registry version: ${saved.version}" }
        require(saved.nextNumber > 0) { "Invalid next hat number" }
        val numbers = hashSetOf<Long>()
        saved.players.forEach { (owner, collection) ->
            require(UUID.fromString(owner).toString() == owner) { "Invalid hat owner: $owner" }
            collection.hats.forEach { hat ->
                require(hat.number > 0 && hat.number < saved.nextNumber) { "Invalid hat number: ${hat.number}" }
                require(hat.hatName.isNotBlank()) { "Hat names must not be blank" }
                require(numbers.add(hat.number)) { "Duplicate hat number: ${hat.number}" }
            }
            require(
                collection.equipped == null || collection.hats.any { it.number == collection.equipped },
            ) { "Equipped hat is not owned by $owner" }
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
