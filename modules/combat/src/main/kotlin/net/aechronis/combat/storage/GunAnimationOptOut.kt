package net.aechronis.combat.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.aechronis.server.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Players who opted out of shader-driven gun animations. */
object GunAnimationOptOut {
    @Serializable
    private data class Saved(
        val version: Int = 1,
        val disabled: Set<String> = emptySet(),
    )

    private val json = Json { encodeDefaults = true }
    private val file = Path.of("combat", "gun-animations.json")

    // Read every tick by GunAnimation; replaced whole after each committed write.
    @Volatile private var players: Set<UUID> = emptySet()

    fun initialize() {
        if (!Files.exists(file)) return
        val saved = json.decodeFromString<Saved>(Files.readString(file))
        require(saved.version == 1) { "Unsupported gun animation preferences version: ${saved.version}" }
        players = saved.disabled.mapTo(HashSet(), UUID::fromString)
    }

    fun disabled(uuid: UUID): Boolean = uuid in players

    @Synchronized
    fun set(
        uuid: UUID,
        enabled: Boolean,
    ) {
        val updated = if (enabled) players - uuid else players + uuid
        if (updated == players) return
        val saved = Saved(disabled = updated.mapTo(sortedSetOf()) { it.toString() })
        Files.createDirectories(file.parent)
        AtomicFiles.writeString(file, json.encodeToString(saved), preservePermissions = true)
        players = updated
    }
}
