package net.aechronis.votifier

import com.vexsoftware.votifier.model.Vote
import com.vexsoftware.votifier.net.VotifierServerBootstrap
import com.vexsoftware.votifier.net.VotifierSession
import com.vexsoftware.votifier.platform.LoggingAdapter
import com.vexsoftware.votifier.platform.VotifierPlugin
import com.vexsoftware.votifier.platform.scheduler.ScheduledExecutorServiceVotifierScheduler
import com.vexsoftware.votifier.platform.scheduler.VotifierScheduler
import com.vexsoftware.votifier.util.KeyCreator
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.timer.TaskSchedule
import java.security.Key
import java.security.KeyPair
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

internal class VotifierPluginAdapter(
    private val options: VotifierOptions,
    private val configStore: VotifierConfigStore,
    private val eventNode: EventNode<Event>,
) : VotifierPlugin {
    private val stopped = AtomicBoolean()
    private val schedulerExecutor =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "votifier-scheduler").also { it.isDaemon = true }
        }
    private val logger = VotifierLogger(options)
    private val scheduler: VotifierScheduler = ScheduledExecutorServiceVotifierScheduler(schedulerExecutor)
    private lateinit var tokens: Map<String, Key>
    private var protocolV1Key: KeyPair? = null
    private var bootstrap: VotifierServerBootstrap? = null

    fun start() {
        check(!stopped.get()) { "Votifier is shut down" }
        val config = configStore.current()
        tokens = config.tokens.mapValues { (_, token) -> KeyCreator.createKeyFrom(token) }
        protocolV1Key = if (config.protocolV1Enabled) configStore.loadOrCreateProtocolV1Key() else null

        val server =
            VotifierServerBootstrap(
                options.bindAddress.hostString,
                options.bindAddress.port,
                this,
                protocolV1Key == null,
            )
        bootstrap = server

        val started = CompletableFuture<Throwable?>()
        server.start { error -> started.complete(error) }
        try {
            started.get(options.dispatchTimeoutMillis, TimeUnit.MILLISECONDS)?.let { throw it }
        } catch (error: TimeoutException) {
            server.shutdown()
            throw IllegalStateException("Timed out binding Votifier to ${options.bindAddress}", error)
        } catch (error: ExecutionException) {
            server.shutdown()
            throw (error.cause ?: error)
        }
    }

    override fun getTokens(): Map<String, Key> = tokens

    override fun getProtocolV1Key(): KeyPair? = protocolV1Key

    override fun getPluginLogger(): LoggingAdapter = logger

    override fun getScheduler(): VotifierScheduler = scheduler

    override fun isDebug(): Boolean = options.debug

    override fun onVoteReceived(
        vote: Vote,
        protocolVersion: VotifierSession.ProtocolVersion,
        remoteAddress: String,
    ) {
        check(!stopped.get()) { "Votifier is shut down" }
        val completion = CompletableFuture<Unit>()
        try {
            MinecraftServer.getSchedulerManager().submitTask {
                try {
                    check(!stopped.get()) { "Votifier is shut down" }
                    eventNode.call(VoteReceivedEvent(vote, protocolVersion, remoteAddress))
                    completion.complete(Unit)
                } catch (error: Throwable) {
                    completion.completeExceptionally(error)
                }
                TaskSchedule.stop()
            }
            completion.get(options.dispatchTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    override fun onError(
        throwable: Throwable,
        voteAlreadyCompleted: Boolean,
        remoteAddress: String,
    ) {
        val phase = if (voteAlreadyCompleted) "after accepting a vote" else "while reading a vote"
        options.logger.log(Level.WARNING, "Votifier error $phase from $remoteAddress", throwable)
    }

    fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        bootstrap?.shutdown()
        schedulerExecutor.shutdownNow()
    }
}

private class VotifierLogger(
    private val options: VotifierOptions,
) : LoggingAdapter {
    override fun error(message: String) = options.logger.severe(message)

    override fun error(
        message: String,
        vararg arguments: Any,
    ) {
        val throwable = arguments.lastOrNull() as? Throwable
        if (throwable == null) {
            options.logger.severe(message.format(*arguments))
        } else {
            options.logger.log(Level.SEVERE, message.format(*arguments.dropLast(1).toTypedArray()), throwable)
        }
    }

    override fun warn(message: String) = options.logger.warning(message)

    override fun warn(
        message: String,
        vararg arguments: Any,
    ) = options.logger.warning(message.format(*arguments))

    override fun info(message: String) = options.logger.info(message)

    override fun info(
        message: String,
        vararg arguments: Any,
    ) = options.logger.info(message.format(*arguments))
}
