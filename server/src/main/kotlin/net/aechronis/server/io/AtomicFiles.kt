package net.aechronis.server.io

import java.io.BufferedWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView

/** Publishes complete sibling files, with explicit permissions and atomicity requirements. */
object AtomicFiles {
    fun write(
        path: Path,
        preservePermissions: Boolean = false,
        writeContents: (BufferedWriter) -> Unit,
    ) {
        withTemporaryFile(path) { temporary ->
            Files.newBufferedWriter(temporary).use(writeContents)
            replace(temporary, path, preservePermissions)
        }
    }

    fun writeString(
        path: Path,
        contents: String,
        preservePermissions: Boolean = false,
    ) {
        withTemporaryFile(path) { temporary ->
            Files.writeString(temporary, contents)
            replace(temporary, path, preservePermissions)
        }
    }

    fun copy(
        source: Path,
        target: Path,
        preservePermissions: Boolean = false,
    ) {
        withTemporaryFile(target) { temporary ->
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            replace(temporary, target, preservePermissions)
        }
    }

    /** Creates a private sibling file; the caller closes its streams before calling [replace]. */
    fun withTemporaryFile(
        target: Path,
        action: (Path) -> Unit,
    ) {
        val parent = target.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${target.fileName}.", ".tmp")
        withTemporaryPath(temporary, action)
    }

    /** Keeps callers that create their own staging file in control of its name and initial permissions. */
    fun withTemporaryPath(
        temporary: Path,
        action: (Path) -> Unit,
    ) {
        var failure: Throwable? = null
        try {
            action(temporary)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            runCatching { Files.deleteIfExists(temporary) }.exceptionOrNull()?.let { cleanupError ->
                failure?.addSuppressed(cleanupError) ?: throw cleanupError
            }
        }
    }

    /** Falls back only when atomic moves are unsupported, unless the caller requires atomic replacement. */
    fun replace(
        temporary: Path,
        target: Path,
        preservePermissions: Boolean = false,
        requireAtomic: Boolean = false,
    ) {
        if (preservePermissions) copyPermissions(target, temporary)
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            if (requireAtomic) throw error
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun copyPermissions(
        source: Path,
        target: Path,
    ) {
        if (!Files.exists(source)) return
        val sourceAttributes = Files.getFileAttributeView(source, PosixFileAttributeView::class.java) ?: return
        val targetAttributes = Files.getFileAttributeView(target, PosixFileAttributeView::class.java) ?: return
        targetAttributes.setPermissions(sourceAttributes.readAttributes().permissions())
    }
}
