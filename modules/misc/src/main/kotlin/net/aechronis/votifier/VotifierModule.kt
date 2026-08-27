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
    fun initialize(options: VotifierOptions = VotifierOptions()) {
        if (started.get() != null) return

        val configStore = VotifierConfigStore(options.dataDirectory)
        configStore.reload()
        val node = EventNode.all("votifier")
        val adapter = VotifierPluginAdapter(options, configStore, node)
        MinecraftServer.getGlobalEventHandler().addChild(node)
        try {
            adapter.start()
            started.set(RunningVotifier(node, adapter))
            MinecraftServer.getSchedulerManager().buildShutdownTask(::shutdown)
        } catch (error: Throwable) {
            adapter.shutdown()
            MinecraftServer.getGlobalEventHandler().removeChild(node)
            throw error
        }
    }

    @Synchronized
    fun shutdown() {
        val running = started.getAndSet(null) ?: return
        running.adapter.shutdown()
        MinecraftServer.getGlobalEventHandler().removeChild(running.eventNode)
    }

    private fun requireRunning(): RunningVotifier = started.get() ?: error("VotifierModule is not initialized")
}

private class RunningVotifier(
    val eventNode: EventNode<Event>,
    val adapter: VotifierPluginAdapter,
)
