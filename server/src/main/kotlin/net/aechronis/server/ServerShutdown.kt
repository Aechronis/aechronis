package net.aechronis.server

import net.aechronis.server.network.ServerNetwork
import net.aechronis.server.resourcepack.ResourcePackServer
import java.util.concurrent.atomic.AtomicBoolean

internal class ShutdownCoordinator(
    private val closeExternalServices: () -> Unit,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private val started = AtomicBoolean()

    @Volatile
    private var stages = Stages({}, {}, {}, {}, {}, {})

    fun configure(
        beginShutdown: () -> Unit,
        stopWorldSaver: () -> Unit,
        persistModules: () -> Unit,
        stopServer: () -> Unit,
        saveCoreWorld: () -> Unit,
        closeModules: () -> Unit,
    ) {
        stages =
            Stages(
                beginShutdown,
                stopWorldSaver,
                persistModules,
                stopServer,
                saveCoreWorld,
                closeModules,
            )
    }

    // Every stage runs even after an earlier one fails: skipping the saves because one module
    // could not prepare would discard everything since the last periodic checkpoint.
    fun shutdown() {
        if (!started.compareAndSet(false, true)) return

        val configured = stages
        runStage("begin module shutdown", configured.beginShutdown)
        runStage("stop the world saver", configured.stopWorldSaver)
        runStage("save modules and the world", configured.persistModules)
        runStage("stop the game server", configured.stopServer)
        // Catches chunks changed between resuming gameplay and the server stopping.
        runStage("save the final core world state", configured.saveCoreWorld)
        runStage("stop modules", configured.closeModules)
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
        val beginShutdown: () -> Unit,
        val stopWorldSaver: () -> Unit,
        val persistModules: () -> Unit,
        val stopServer: () -> Unit,
        val saveCoreWorld: () -> Unit,
        val closeModules: () -> Unit,
    )
}

object ServerShutdown {
    private lateinit var coordinator: ShutdownCoordinator

    @Volatile private var network: ServerNetwork? = null

    fun registerNetwork(network: ServerNetwork) {
        check(this.network == null) { "The server network is already installed" }
        this.network = network
    }

    fun install(resourcePackServer: ResourcePackServer) {
        check(!::coordinator.isInitialized) { "Server shutdown is already installed" }
        coordinator =
            ShutdownCoordinator({
                try {
                    network?.close()
                } finally {
                    resourcePackServer.close()
                }
            }) { stage, error ->
                System.err.println("Failed to $stage during shutdown: ${error.message}")
                error.printStackTrace()
            }
        Runtime.getRuntime().addShutdownHook(Thread({ shutdownCompletion().join() }, "server-shutdown"))
    }

    fun configure(
        beginShutdown: () -> Unit,
        stopWorldSaver: () -> Unit,
        persistModules: () -> Unit,
        stopServer: () -> Unit,
        saveCoreWorld: () -> Unit,
        closeModules: () -> Unit,
    ) {
        coordinator.configure(
            {
                network?.stopAccepting()
                beginShutdown()
            },
            stopWorldSaver,
            persistModules,
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
