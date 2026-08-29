package net.aechronis.vanilla.managers

import java.io.BufferedWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView

/** Replaces lifecycle snapshots only after their complete contents have reached a sibling file. */
internal object AtomicFiles {
    fun write(
        path: Path,
        writeContents: (BufferedWriter) -> Unit,
    ) {
        val parent = path.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
        var writeFailure: Throwable? = null
        try {
            Files.newBufferedWriter(temporary).use(writeContents)
            replace(temporary, path)
        } catch (error: Throwable) {
            writeFailure = error
            throw error
        } finally {
            runCatching { Files.deleteIfExists(temporary) }.exceptionOrNull()?.let { cleanupError ->
                writeFailure?.addSuppressed(cleanupError) ?: throw cleanupError
            }
        }
    }

    private fun replace(
        temporary: Path,
        target: Path,
    ) {
        preservePermissions(target, temporary)
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
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
