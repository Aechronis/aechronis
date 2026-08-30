package net.aechronis.server.resourcepack

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.jar.JarFile

object EmbeddedResourcePack {
    private const val EMBEDDED_ROOT = "embedded-resource-pack/"

    fun installIfPresent(
        directory: Path?,
        owner: Class<*>,
    ): Path? {
        val location = codeLocation(owner)
        if (Files.isDirectory(location, LinkOption.NOFOLLOW_LINKS)) {
            return findLivePack(location)?.also { println("[ResourcePack] using live pack at $it") }
        }
        require(Files.isRegularFile(location, LinkOption.NOFOLLOW_LINKS)) {
            "Cannot locate the module JAR: $location"
        }
        val hasPack = JarFile(location.toFile()).use { it.getJarEntry("${EMBEDDED_ROOT}pack.mcmeta") != null }
        if (!hasPack) return null
        val destination = checkNotNull(directory) { "Resource-pack directory is unavailable for ${owner.name}" }
        return install(destination, location)
    }

    internal fun install(
        directory: Path,
        codeLocation: Path,
    ): Path {
        val destination = directory.toAbsolutePath().normalize()
        val jar = codeLocation.toAbsolutePath().normalize()
        if (Files.isDirectory(jar, LinkOption.NOFOLLOW_LINKS)) {
            val livePack = findLivePack(jar)
            require(livePack != null) {
                "Cannot locate a live resource-pack directory above exploded classes: $jar"
            }
            println("[ResourcePack] using live pack at $livePack")
            return livePack
        }
        require(Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            "Cannot locate the module JAR: $jar"
        }

        val parent = destination.parent ?: Path.of(".").toAbsolutePath().normalize()
        Files.createDirectories(parent)
        val staging = Files.createTempDirectory(parent, ".aechronis-resource-pack-")
        val backup = parent.resolve(".${destination.fileName}.backup")
        var backedUp = false
        var installed = false
        try {
            JarFile(jar.toFile()).use { archive ->
                extract(archive, staging)
            }
            require(Files.isRegularFile(staging.resolve("pack.mcmeta"), LinkOption.NOFOLLOW_LINKS)) {
                "The server JAR does not contain a valid embedded resource pack"
            }

            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                deleteIfExists(backup)
                move(destination, backup)
                backedUp = true
            }
            move(staging, destination)
            installed = true
            if (backedUp) deleteRecursively(backup)
            println("[ResourcePack] extracted embedded pack to $destination")
            return destination
        } catch (exception: Exception) {
            if (installed) deleteIfExists(destination)
            if (backedUp && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) move(backup, destination)
            throw exception
        } finally {
            deleteRecursively(staging)
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) deleteRecursively(backup)
        }
    }

    fun defaultDirectory(
        codeLocation: Path = codeLocation(EmbeddedResourcePack::class.java),
        workingDirectory: Path = Path.of("."),
    ): Path {
        val location = codeLocation.toAbsolutePath().normalize()
        return if (Files.isRegularFile(location, LinkOption.NOFOLLOW_LINKS)) {
            location.parent.resolve("resource-packs")
        } else {
            workingDirectory.toAbsolutePath().normalize().resolve("run/resource-packs")
        }
    }

    private fun codeLocation(owner: Class<*>): Path {
        val location =
            owner
                .protectionDomain
                .codeSource
                .location
                .toURI()
        return Path.of(location).toAbsolutePath().normalize()
    }

    private fun findLivePack(codeLocation: Path): Path? {
        var ancestor: Path? = codeLocation
        while (ancestor != null) {
            val candidate = ancestor.resolve("resource-pack")
            if (Files.isRegularFile(candidate.resolve("pack.mcmeta"), LinkOption.NOFOLLOW_LINKS)) {
                return candidate
            }
            ancestor = ancestor.parent
        }
        return null
    }

    private fun extract(
        archive: JarFile,
        destination: Path,
    ) {
        val destinationRoot = destination.toAbsolutePath().normalize()
        val entries = archive.entries()
        var foundPackFile = false
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.name.startsWith(EMBEDDED_ROOT) || entry.name == EMBEDDED_ROOT) continue
            val relativeName = entry.name.removePrefix(EMBEDDED_ROOT)
            val output = destinationRoot.resolve(relativeName).normalize()
            require(output.startsWith(destinationRoot) && output != destinationRoot) {
                "Embedded resource pack contains an unsafe path: ${entry.name}"
            }
            if (entry.isDirectory) {
                Files.createDirectories(output)
            } else {
                Files.createDirectories(output.parent)
                archive.getInputStream(entry).use { input ->
                    Files.newOutputStream(output).use { outputStream -> input.copyTo(outputStream) }
                }
                foundPackFile = foundPackFile || relativeName == "pack.mcmeta"
            }
        }
        require(foundPackFile) { "The server JAR does not contain pack.mcmeta" }
    }

    private fun move(
        source: Path,
        destination: Path,
    ) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        }
    }

    private fun deleteIfExists(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(path)
        } else {
            Files.deleteIfExists(path)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
