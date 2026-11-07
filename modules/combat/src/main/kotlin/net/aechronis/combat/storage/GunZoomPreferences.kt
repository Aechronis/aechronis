package net.aechronis.combat.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.aechronis.server.io.AtomicFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Per-player aim zoom preferences. Players without an entry use [Preference] defaults. */
object GunZoomPreferences {
    /** [minSlot] and [maxSlot] index GunZoom.LEVELS (0 = 1x .. 5 = 6x); aiming starts at [minSlot]. */
    @Serializable
    data class Preference(
        val enabled: Boolean = true,
        val minSlot: Int = 0,
        val maxSlot: Int = 4,
    )

    @Serializable
    private data class Saved(
        val version: Int = 1,
        val players: Map<String, Preference> = emptyMap(),
    )

    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    private val file = Path.of("combat", "gun-zoom.json")

    // Read on every scroll and tick; replaced whole after each committed write.
    @Volatile private var players: Map<UUID, Preference> = emptyMap()

    fun initialize() {
        if (!Files.exists(file)) return
        // A cosmetic preference must never stop combat from starting; fall back to defaults.
        runCatching {
            val saved = json.decodeFromString<Saved>(Files.readString(file))
            require(saved.version == 1) { "Unsupported gun zoom preferences version: ${saved.version}" }
            players = saved.players.mapKeys { UUID.fromString(it.key) }
        }.onFailure { System.err.println("[Combat] Ignoring unreadable $file: ${it.message}") }
    }

    fun get(uuid: UUID): Preference = players[uuid] ?: Preference()

    /** Returns false, leaving the preference unchanged, when it could not be saved. */
    @Synchronized
    fun update(
        uuid: UUID,
        change: (Preference) -> Preference,
    ): Boolean {
        val preference = change(get(uuid))
        if (preference == get(uuid)) return true
        val updated = players + (uuid to preference)
        val saved = Saved(players = updated.entries.associateTo(sortedMapOf()) { it.key.toString() to it.value })
        try {
            Files.createDirectories(file.parent)
            AtomicFiles.writeString(file, json.encodeToString(saved), preservePermissions = true)
        } catch (error: IOException) {
            System.err.println("Could not save gun zoom preferences: $error")
            return false
        }
        players = updated
        return true
    }
}
