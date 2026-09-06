package net.aechronis.server

import net.aechronis.server.resourcepack.ResourcePackServer
import java.util.concurrent.atomic.AtomicBoolean

internal class ShutdownCoordinator(
    private val closeExternalServices: () -> Unit,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private val started = AtomicBoolean()

    @Volatile
    private var stages = Stages({}, {}, {}, {}, {}, {}, {}, {}, {})

    fun configure(
        beginShutdown: () -> Unit,
        stopWorldSaver: () -> Unit,
        closeCraftingStore: () -> Unit,
        closeVotifier: () -> Unit,
        prepareModules: () -> Unit,
        saveModuleState: () -> Unit,
        stopServer: () -> Unit,
        saveCoreWorld: () -> Unit,
        closeModules: () -> Unit,
    ) {
        stages =
            Stages(
                beginShutdown,
                stopWorldSaver,
                closeCraftingStore,
                closeVotifier,
                prepareModules,
                saveModuleState,
                stopServer,
                saveCoreWorld,
                closeModules,
            )
    }

    fun shutdown() {
        if (!started.compareAndSet(false, true)) return

        val configured = stages
        runStage("begin module shutdown", configured.beginShutdown)
        runStage("stop the world saver", configured.stopWorldSaver)
        runStage("close CraftingStore", configured.closeCraftingStore)
        runStage("close Votifier", configured.closeVotifier)
        val prepared = runStage("quiesce module work", configured.prepareModules)
        if (prepared) {
            runStage("save live module state", configured.saveModuleState)
            runStage("stop the game server", configured.stopServer)
            runStage("save the final core world state", configured.saveCoreWorld)
            runStage("stop modules", configured.closeModules)
        } else {
            // Persisting while a module can still mutate the world would create an internally
            // inconsistent checkpoint. Fail closed, but continue stopping every live service.
            runStage("stop the game server", configured.stopServer)
            runStage("stop modules", configured.closeModules)
        }
        runStage("close external services", closeExternalServices)
    }

    private fun runStage(
        description: String,
        action: () -> Unit,
    ): Boolean =
        try {
            action()
            true
        } catch (error: Throwable) {
            reportFailure(description, error)
            false
        }

    private data class Stages(
        val beginShutdown: () -> Unit,
        val stopWorldSaver: () -> Unit,
        val closeCraftingStore: () -> Unit,
        val closeVotifier: () -> Unit,
        val prepareModules: () -> Unit,
        val saveModuleState: () -> Unit,
        val stopServer: () -> Unit,
        val saveCoreWorld: () -> Unit,
        val closeModules: () -> Unit,
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
        Runtime.getRuntime().addShutdownHook(Thread({ shutdownCompletion().join() }, "server-shutdown"))
    }

    fun configure(
        beginShutdown: () -> Unit,
        stopWorldSaver: () -> Unit,
        closeCraftingStore: () -> Unit,
        closeVotifier: () -> Unit,
        prepareModules: () -> Unit,
        saveModuleState: () -> Unit,
        stopServer: () -> Unit,
        saveCoreWorld: () -> Unit,
        closeModules: () -> Unit,
    ) {
        coordinator.configure(
            beginShutdown,
            stopWorldSaver,
            closeCraftingStore,
            closeVotifier,
            prepareModules,
            saveModuleState,
            stopServer,
            saveCoreWorld,
            closeModules,
        )
    }

    private var shutdownFuture: java.util.concurrent.CompletableFuture<Void>? = null

    @Synchronized
    private fun shutdownCompletion(): java.util.concurrent.CompletableFuture<Void> {
        shutdownFuture?.let { return it }
        val future = java.util.concurrent.CompletableFuture<Void>()
        shutdownFuture = future
        Thread.ofVirtual().name("server-shutdown-coordinator").start {
            try {
                coordinator.shutdown()
                future.complete(null)
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future
    }

    fun shutdown() {
        shutdownCompletion()
    }
}
