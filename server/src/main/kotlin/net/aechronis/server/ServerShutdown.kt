package net.aechronis.server

import net.aechronis.server.resourcepack.ResourcePackServer
import java.util.concurrent.atomic.AtomicBoolean

internal class ShutdownCoordinator(
    private val closeExternalServices: () -> Unit,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private val started = AtomicBoolean()

    @Volatile
    private var stages = Stages({}, {}, {}, {})

    fun configure(
        saveState: () -> Unit,
        stopServer: () -> Unit,
        saveWorld: () -> Unit,
        closeLogger: () -> Unit,
    ) {
        stages = Stages(saveState, stopServer, saveWorld, closeLogger)
    }

    fun shutdown() {
        if (!started.compareAndSet(false, true)) return

        val configured = stages
        runStage("save live state", configured.saveState)
        runStage("stop the game server", configured.stopServer)
        runStage("save the final world state", configured.saveWorld)
        runStage("flush and close logger", configured.closeLogger)
        runStage("close external services", closeExternalServices)
    }

    private fun runStage(
        description: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: Throwable) {
            reportFailure(description, error)
        }
    }

    private data class Stages(
        val saveState: () -> Unit,
        val stopServer: () -> Unit,
        val saveWorld: () -> Unit,
        val closeLogger: () -> Unit,
    )
}

object ServerShutdown {
    private lateinit var coordinator: ShutdownCoordinator

    fun install(resourcePackServer: ResourcePackServer) {
        check(!::coordinator.isInitialized) { "Server shutdown is already installed" }
        coordinator =
            ShutdownCoordinator(resourcePackServer::close) { stage, error ->
                System.err.println("Failed to $stage during shutdown: ${error.message}")
                error.printStackTrace()
            }
        Runtime.getRuntime().addShutdownHook(Thread(::shutdown, "server-shutdown"))
    }

    fun configure(
        saveState: () -> Unit,
        stopServer: () -> Unit,
        saveWorld: () -> Unit,
        closeLogger: () -> Unit,
    ) {
        coordinator.configure(saveState, stopServer, saveWorld, closeLogger)
    }

    fun shutdown() {
        coordinator.shutdown()
    }
}
