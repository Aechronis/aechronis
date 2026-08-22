package net.aechronis.nodes.tasks

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object AtomicFiles {
    fun writeString(
        path: Path,
        contents: String,
    ) {
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, contents)
            replace(temporary, path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun copy(
        source: Path,
        target: Path,
    ) {
        val parent = target.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            replace(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun replace(
        temporary: Path,
        target: Path,
    ) {
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
