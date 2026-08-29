package net.aechronis.server.resourcepack

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourcePackArchiveTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun `archive is deterministic when source timestamps change`() {
        val metadata = directory.resolve("pack.mcmeta")
        val texture = directory.resolve("assets/aechronis/textures/example.txt")
        Files.createDirectories(texture.parent)
        Files.writeString(metadata, "{\"pack\":{\"pack_format\":1,\"description\":\"test\"}}")
        Files.writeString(texture, "same content")

        setTimestamps(FileTime.from(Instant.parse("2020-01-01T00:00:00Z")), metadata, texture)
        ResourcePackArchive.create(directory).use { first ->
            setTimestamps(FileTime.from(Instant.parse("2026-08-28T12:34:56Z")), metadata, texture)
            ResourcePackArchive.create(directory).use { second ->
                assertEquals(first.hash, second.hash)
                assertContentEquals(Files.readAllBytes(first.path), Files.readAllBytes(second.path))
            }
        }
    }

    @Test
    fun `HTTP executor shutdown is bounded when a response ignores interruption`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Simulate a response stream which does not honor interruption.
                }
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        try {
            assertFailsWith<IllegalStateException> {
                shutdownResourcePackExecutor(
                    executor,
                    gracefulTimeout = Duration.ofMillis(20),
                    forcedTimeout = Duration.ofMillis(20),
                )
            }
        } finally {
            release.countDown()
            shutdownResourcePackExecutor(
                executor,
                gracefulTimeout = Duration.ofSeconds(1),
                forcedTimeout = Duration.ofSeconds(1),
            )
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    private fun setTimestamps(
        timestamp: FileTime,
        vararg files: Path,
    ) {
        files.forEach { Files.setLastModifiedTime(it, timestamp) }
    }
}
