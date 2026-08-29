package net.aechronis.nodes.tasks

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals

class AtomicFilesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `atomic write replaces prior contents without leaving a temporary file`() {
        val target = directory.resolve("towns.json")
        Files.writeString(target, "old")

        AtomicFiles.writeString(target, "new")

        assertEquals("new", Files.readString(target))
        Files.list(directory).use { files ->
            assertEquals(listOf(target), files.toList())
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `atomic write preserves existing permissions`() {
        val target = directory.resolve("towns.json")
        val permissions = PosixFilePermissions.fromString("rw-r--r--")
        Files.writeString(target, "old")
        Files.setPosixFilePermissions(target, permissions)

        AtomicFiles.writeString(target, "new")

        assertEquals(permissions, Files.getPosixFilePermissions(target))
    }

    @Test
    fun `atomic copy replaces an existing backup`() {
        val source = directory.resolve("towns.json")
        val backup = directory.resolve("backup/towns.json")
        Files.writeString(source, "new")
        Files.createDirectories(backup.parent)
        Files.writeString(backup, "old")

        AtomicFiles.copy(source, backup)

        assertEquals("new", Files.readString(backup))
    }
}
