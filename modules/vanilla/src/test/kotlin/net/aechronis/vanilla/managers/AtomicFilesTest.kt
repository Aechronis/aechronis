package net.aechronis.vanilla.managers

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicFilesTest {
    @Test
    fun `complete snapshot replaces the prior file without leaving a temporary file`(
        @TempDir directory: Path,
    ) {
        val target = directory.resolve("state.json")
        Files.writeString(target, "old")

        AtomicFiles.write(target) { writer -> writer.write("new") }

        assertEquals("new", Files.readString(target))
        Files.list(directory).use { files -> assertEquals(listOf(target), files.toList()) }
    }

    @Test
    fun `failed snapshot leaves the prior file intact`(
        @TempDir directory: Path,
    ) {
        val target = directory.resolve("state.json")
        Files.writeString(target, "old")

        assertFailsWith<IllegalStateException> {
            AtomicFiles.write(target) { writer ->
                writer.write("partial")
                error("write failed")
            }
        }

        assertEquals("old", Files.readString(target))
        Files.list(directory).use { files -> assertEquals(listOf(target), files.toList()) }
    }
}
