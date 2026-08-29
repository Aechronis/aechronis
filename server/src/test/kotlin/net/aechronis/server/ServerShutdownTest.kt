package net.aechronis.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerShutdownTest {
    @Test
    fun `shutdown runs every stage once even when one fails`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val coordinator =
            ShutdownCoordinator(
                closeExternalServices = { calls += "external" },
                reportFailure = { stage, _ -> failures += stage },
            )
        coordinator.configure(
            beginShutdown = { calls += "begin" },
            stopWorldSaver = { calls += "world-saver" },
            closeCraftingStore = {
                calls += "craftingstore"
                error("expected")
            },
            closeVotifier = { calls += "votifier" },
            prepareModules = { calls += "prepare-modules" },
            saveModuleState = { calls += "module-state" },
            stopServer = { calls += "server" },
            saveCoreWorld = { calls += "core-world" },
            closeModules = { calls += "modules" },
        )

        coordinator.shutdown()
        coordinator.shutdown()

        assertEquals(
            listOf(
                "begin",
                "world-saver",
                "craftingstore",
                "votifier",
                "prepare-modules",
                "module-state",
                "server",
                "core-world",
                "modules",
                "external",
            ),
            calls,
        )
        assertEquals(listOf("close CraftingStore"), failures)
    }

    @Test
    fun `shutdown skips persistence when module work cannot quiesce`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val coordinator =
            ShutdownCoordinator(
                closeExternalServices = { calls += "external" },
                reportFailure = { stage, _ -> failures += stage },
            )
        coordinator.configure(
            beginShutdown = { calls += "begin" },
            stopWorldSaver = { calls += "world-saver" },
            closeCraftingStore = { calls += "craftingstore" },
            closeVotifier = { calls += "votifier" },
            prepareModules = {
                calls += "prepare-modules"
                error("still active")
            },
            saveModuleState = { calls += "module-state" },
            stopServer = { calls += "server" },
            saveCoreWorld = { calls += "core-world" },
            closeModules = { calls += "modules" },
        )

        coordinator.shutdown()

        assertEquals(
            listOf("begin", "world-saver", "craftingstore", "votifier", "prepare-modules", "server", "modules", "external"),
            calls,
        )
        assertEquals(listOf("quiesce module work"), failures)
    }
}
