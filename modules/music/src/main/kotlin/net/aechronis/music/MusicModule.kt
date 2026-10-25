package net.aechronis.music

import net.aechronis.music.commands.Disc
import net.aechronis.music.managers.Music
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class MusicModule : AechronisModule {
    override val id = "music"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        val config = takeConfiguration()
        val directory = Path.of(config.path)
        if (config.path == "music") migrateLegacyDirectory(directory)
        Music.init(directory, config.musicDiscs)
        Music.registerResourcePack(context)
        ModuleCommands.register(Disc())
    }

    override fun shutdown(context: ModuleContext) = Music.shutdown()

    private fun migrateLegacyDirectory(directory: Path) {
        val legacy = Path.of("vanilla", "music")
        if (!Files.notExists(directory, LinkOption.NOFOLLOW_LINKS) || !Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) return
        val sourceRoot = legacy.toRealPath()
        val target = directory.toAbsolutePath()
        Files.createDirectories(target.parent)
        val staging = Files.createTempDirectory(target.parent, ".music-migration-")
        try {
            Files.walk(sourceRoot).use { entries ->
                entries.forEach { source ->
                    val destination = staging.resolve(sourceRoot.relativize(source))
                    if (Files.isSymbolicLink(source)) {
                        // Moving a relative link to a different directory must not change its target.
                        val linked =
                            source.parent
                                .resolve(Files.readSymbolicLink(source))
                                .normalize()
                                .toAbsolutePath()
                        val relocated =
                            if (linked.startsWith(sourceRoot)) {
                                target.resolve(sourceRoot.relativize(linked))
                            } else {
                                linked
                            }
                        Files.createSymbolicLink(destination, relocated)
                    } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(destination)
                    } else {
                        Files.copy(source, destination, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
            // Publish the complete copy without replacing a directory created in the meantime.
            Files.move(staging, target)
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                Files.walk(staging).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }

    companion object {
        private var configured: MusicConfig? = null

        fun configure(config: MusicConfig) {
            check(configured == null) { "Music was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): MusicConfig = (configured ?: MusicConfig()).also { configured = null }
    }
}
