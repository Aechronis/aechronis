package net.aechronis.treefeller

import net.aechronis.server.io.AtomicFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Players who turned tree felling off in /settings. One UUID per line. */
object TreeFellerOptOut {
    private val file = Path.of("treefeller", "disabled.txt")

    @Volatile private var players: Set<UUID> = emptySet()

    fun initialize() {
        if (!Files.exists(file)) return
        // A preference file must never stop the module from starting; fall back to everyone enabled.
        runCatching {
            // Skip bad lines one by one so a single damaged entry doesn't re-enable felling for everyone.
            players =
                Files.readAllLines(file).filter { it.isNotBlank() }.mapNotNullTo(HashSet()) { line ->
                    runCatching { UUID.fromString(line.trim()) }
                        .onFailure { System.err.println("[TreeFeller] Skipping bad entry in $file: $line") }
                        .getOrNull()
                }
        }.onFailure { System.err.println("[TreeFeller] Ignoring unreadable $file: ${it.message}") }
    }

    fun disabled(uuid: UUID): Boolean = uuid in players

    /** Returns false, leaving the preference unchanged, when it could not be saved. */
    @Synchronized
    fun set(
        uuid: UUID,
        enabled: Boolean,
    ): Boolean {
        val updated = if (enabled) players - uuid else players + uuid
        if (updated == players) return true
        try {
            Files.createDirectories(file.parent)
            AtomicFiles.writeString(file, updated.map { it.toString() }.sorted().joinToString("\n"), preservePermissions = true)
        } catch (error: IOException) {
            System.err.println("Could not save tree feller preferences: $error")
            return false
        }
        players = updated
        return true
    }
}
