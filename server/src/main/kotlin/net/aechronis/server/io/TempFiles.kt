package net.aechronis.server.io

import net.aechronis.server.modules.deleteModuleTree
import java.nio.file.Files
import java.nio.file.Path

/**
 * Temp space owned by this server. Module JAR copies and pack archives are only removed on a clean
 * close, so a crash or kill used to leave them in the system temp directory (RAM-backed on prod)
 * forever. The server wipes its own directory at startup instead; tests keep the system default.
 */
internal object TempFiles {
    @Volatile
    var root: Path = Path.of(System.getProperty("java.io.tmpdir"))
        private set

    fun reset(directory: Path) {
        // Absolute: module staging matches JAR paths against class code-source locations.
        val absolute = directory.toAbsolutePath().normalize()
        deleteModuleTree(absolute)
        root = Files.createDirectories(absolute)
        // sqlite-jdbc (grimac) extracts its native library per start too.
        System.setProperty("org.sqlite.tmpdir", absolute.toString())
    }
}
