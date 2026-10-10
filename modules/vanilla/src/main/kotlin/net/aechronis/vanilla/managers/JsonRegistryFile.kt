package net.aechronis.vanilla.managers

import net.aechronis.server.io.AtomicFiles
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Publishes fully decoded registries and preserves unreadable files until a successful reload. */
internal class JsonRegistryFile(
    private val name: String,
) {
    private var path: Path? = null

    @Volatile
    var canSave = false
        private set

    @Synchronized
    fun <K, V> load(
        path: Path,
        registry: MutableMap<K, V>,
        decode: (String) -> Map<K, V>,
    ) {
        this.path = path
        canSave = false
        try {
            val loaded = if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) emptyMap() else decode(Files.readString(path))
            synchronized(registry) {
                registry.clear()
                registry.putAll(loaded)
            }
            canSave = true
        } catch (error: Exception) {
            System.err.println(
                "Failed to load $name from $path: ${error.message}. " +
                    "Saving and configuration edits are disabled until a successful reload.",
            )
        }
    }

    @Synchronized
    fun save(encode: () -> String) {
        val target = path ?: return
        if (!canSave) return
        AtomicFiles.writeString(target, encode(), preservePermissions = true)
    }
}
