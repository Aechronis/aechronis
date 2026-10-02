package net.aechronis.votifier

import com.vexsoftware.votifier.model.Vote
import java.util.concurrent.atomic.AtomicReference

object Votifier {
    private val started = AtomicReference<RunningVotifier?>()

    @Synchronized
    fun initialize(
        options: VotifierOptions = VotifierOptions(),
        onVote: (Vote) -> Unit,
    ) {
        started.get()?.let { running ->
            check(!running.cleanupStarted) { "Votifier cleanup is incomplete" }
            check(running.fullyStarted) { "Votifier initialization is incomplete" }
            return
        }

        val configStore = VotifierConfigStore(options.dataDirectory)
        configStore.reload()
        val adapter = VotifierPluginAdapter(options, configStore, onVote)
        val running = RunningVotifier(adapter)
        started.set(running)
        try {
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
        running.adapter.shutdown()
        started.compareAndSet(running, null)
    }
}

private class RunningVotifier(
    val adapter: VotifierPluginAdapter,
) {
    @Volatile var fullyStarted = false

    @Volatile var cleanupStarted = false
}
