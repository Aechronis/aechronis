package net.aechronis.chat

import net.aechronis.server.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Ignore changes are published durably before becoming visible to chat commands. */
class IgnoreStore(
    private val directory: Path,
) {
    private val ignored = HashMap<UUID, Set<UUID>>()

    fun initialize() {
        Files.createDirectories(directory)
        ignored.clear()
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".txt") }.forEach { path ->
                val uuid = UUID.fromString(path.fileName.toString().removeSuffix(".txt"))
                ignored[uuid] =
                    Files
                        .readAllLines(path)
                        .filter(String::isNotBlank)
                        .map(UUID::fromString)
                        .toSet()
            }
        }
    }

    @Synchronized
    fun isBlocked(
        a: UUID,
        b: UUID,
    ): Boolean = ignored[a]?.contains(b) == true || ignored[b]?.contains(a) == true

    /** Returns how many targets were removed from the ignore list. */
    @Synchronized
    fun toggle(
        player: UUID,
        targets: List<UUID>,
    ): Int {
        val updated = ignored[player].orEmpty().toMutableSet()
        var removed = 0
        targets.filter { it != player }.forEach { target ->
            if (updated.remove(target)) removed++ else updated.add(target)
        }
        write(player, updated)
        ignored[player] = updated
        return removed
    }

    private fun file(player: UUID): Path = directory.resolve("$player.txt")

    private fun write(
        player: UUID,
        entries: Set<UUID>,
    ) {
        AtomicFiles.writeString(file(player), entries.map(UUID::toString).sorted().joinToString("\n"))
    }
}
