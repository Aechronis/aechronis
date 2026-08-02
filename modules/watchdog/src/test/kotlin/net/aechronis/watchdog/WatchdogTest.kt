package net.aechronis.watchdog

import net.aechronis.utils.createTestServer
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchdogTest {
    @BeforeAll
    fun setUp() {
        createTestServer(
            address = "0.0.0.0",
            port = 25565,
            auth = Auth.Offline(),
        )
        Watchdog.initialize()
    }

    @Test
    fun `starts the watchdog test server`() {
        assertTrue(MinecraftServer.isStarted())
    }

    @AfterAll
    fun tearDown() {
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }

        MinecraftServer.stopCleanly()
    }
}
