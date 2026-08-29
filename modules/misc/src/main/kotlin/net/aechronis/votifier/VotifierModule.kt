package net.aechronis.votifier

import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import java.util.concurrent.atomic.AtomicReference

object VotifierModule {
    private val started = AtomicReference<RunningVotifier?>()

    val eventNode: EventNode<Event>
        get() = requireRunning().eventNode

    @Synchronized
    fun initialize(
        options: VotifierOptions = VotifierOptions(),
        configureEventNode: (EventNode<Event>) -> Unit = {},
        attachEventNode: (EventNode<Event>) -> Unit = { node ->
            MinecraftServer.getGlobalEventHandler().addChild(node)
        },
    ) {
        started.get()?.let { running ->
            check(!running.cleanupStarted) { "VotifierModule cleanup is incomplete" }
            check(running.fullyStarted) { "VotifierModule initialization is incomplete" }
            return
        }

        val configStore = VotifierConfigStore(options.dataDirectory)
        configStore.reload()
        val node = EventNode.all("votifier")
        val adapter = VotifierPluginAdapter(options, configStore, node)
        val running = RunningVotifier(node, adapter)
        started.set(running)
        try {
            configureEventNode(node)
            running.eventNodeCleanupPending = true
            attachEventNode(node)
            adapter.start()
            running.fullyStarted = true
        } catch (error: Throwable) {
            runCatching(::shutdown).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    @Synchronized
    fun shutdown() {
        val running = started.get() ?: return
        running.cleanupStarted = true
        var failure: Throwable? = null

        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }

        if (!running.adapterStopped) {
            cleanup {
                running.adapter.shutdown()
                running.adapterStopped = true
            }
        }
        if (running.eventNodeCleanupPending) {
            cleanup {
                MinecraftServer.getGlobalEventHandler().removeChild(running.eventNode)
                running.eventNodeCleanupPending = false
            }
        }
        if (!running.eventNodeCleanupPending && running.adapterStopped) started.compareAndSet(running, null)
        failure?.let { throw it }
    }

    private fun requireRunning(): RunningVotifier {
        val running = started.get() ?: error("VotifierModule is not initialized")
        check(running.fullyStarted && !running.cleanupStarted) { "VotifierModule is not running" }
        return running
    }
}

private class RunningVotifier(
    val eventNode: EventNode<Event>,
    val adapter: VotifierPluginAdapter,
) {
    @Volatile var fullyStarted = false

    @Volatile var cleanupStarted = false
    var eventNodeCleanupPending = false
    var adapterStopped = false
}
