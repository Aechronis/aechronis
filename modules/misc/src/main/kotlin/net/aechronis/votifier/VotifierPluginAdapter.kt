package net.aechronis.votifier

import com.vexsoftware.votifier.model.Vote
import com.vexsoftware.votifier.net.VotifierServerBootstrap
import com.vexsoftware.votifier.net.VotifierSession
import com.vexsoftware.votifier.platform.LoggingAdapter
import com.vexsoftware.votifier.platform.VotifierPlugin
import com.vexsoftware.votifier.platform.scheduler.ScheduledExecutorServiceVotifierScheduler
import com.vexsoftware.votifier.platform.scheduler.VotifierScheduler
import com.vexsoftware.votifier.util.KeyCreator
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.timer.TaskSchedule
import java.lang.reflect.Field
import java.security.Key
import java.security.KeyPair
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level

internal class VotifierPluginAdapter(
    private val options: VotifierOptions,
    private val configStore: VotifierConfigStore,
    private val eventNode: EventNode<Event>,
) : VotifierPlugin {
    private val stopped = AtomicBoolean()
    private val lifecycleLock = ReentrantLock()
    private val schedulerExecutor =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "votifier-scheduler").also { it.isDaemon = true }
        }
    private val logger = VotifierLogger(options)
    private val scheduler: VotifierScheduler = ScheduledExecutorServiceVotifierScheduler(schedulerExecutor)
    private lateinit var tokens: Map<String, Key>
    private var protocolV1Key: KeyPair? = null
    private var bootstrap: VotifierServerBootstrap? = null
    private var bootstrapEventLoops = emptyList<EventLoopGroup>()
    private var lifecycleQuiesced = false
    private var schedulerStopped = false

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
        bootstrapEventLoops = votifierEventLoops(server)

        val started = CompletableFuture<Throwable?>()
        server.start { error -> started.complete(error) }
        try {
            started.get(options.dispatchTimeoutMillis, TimeUnit.MILLISECONDS)?.let { throw it }
        } catch (error: TimeoutException) {
            throw IllegalStateException("Timed out binding Votifier to ${options.bindAddress}", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while binding Votifier to ${options.bindAddress}", error)
        } catch (error: ExecutionException) {
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
                synchronized(completion) {
                    if (!completion.isDone) {
                        try {
                            lifecycleLock.lock()
                            try {
                                check(!stopped.get()) { "Votifier is shut down" }
                                eventNode.call(VoteReceivedEvent(vote, protocolVersion, remoteAddress))
                                completion.complete(Unit)
                            } finally {
                                lifecycleLock.unlock()
                            }
                        } catch (error: Throwable) {
                            completion.completeExceptionally(error)
                        }
                    }
                }
                TaskSchedule.stop()
            }
            completion.get(options.dispatchTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            if (!expirePending(completion, error)) {
                completion.join()
                return
            }
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!expirePending(completion, error)) {
                completion.join()
                return
            }
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

    @Synchronized
    fun shutdown() {
        stopped.set(true)
        var failure: Throwable? = null

        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }

        bootstrap?.let { server ->
            cleanup {
                stopBootstrap(server)
                bootstrap = null
                bootstrapEventLoops = emptyList()
            }
        }
        if (!lifecycleQuiesced) {
            cleanup {
                awaitLifecycleQuiescence()
                lifecycleQuiesced = true
            }
        }
        if (!schedulerStopped) {
            cleanup {
                stopScheduler()
                schedulerStopped = true
            }
        }
        failure?.let { throw it }
    }

    private fun awaitLifecycleQuiescence() {
        try {
            check(lifecycleLock.tryLock(LIFECYCLE_QUIESCENCE_SECONDS, TimeUnit.SECONDS)) {
                "Votifier lifecycle work did not quiesce"
            }
            lifecycleLock.unlock()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while quiescing Votifier lifecycle work", error)
        }
    }

    private fun stopBootstrap(server: VotifierServerBootstrap) {
        var failure: Throwable? = null
        var interrupted = false

        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                if (error is InterruptedException) interrupted = true
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOOTSTRAP_SHUTDOWN_SECONDS)
        val channelClose =
            runCatching { votifierServerChannel(server)?.close() }.getOrElse { error ->
                failure = error
                null
            }
        bootstrapEventLoops.forEach { eventLoop ->
            cleanup {
                eventLoop.shutdownGracefully(0, BOOTSTRAP_SHUTDOWN_SECONDS, TimeUnit.SECONDS)
            }
        }
        channelClose?.let { closeFuture ->
            cleanup {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && closeFuture.await(remaining, TimeUnit.NANOSECONDS)) {
                    "Votifier server channel did not close"
                }
            }
        }
        bootstrapEventLoops.forEach { eventLoop ->
            cleanup {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && eventLoop.terminationFuture().await(remaining, TimeUnit.NANOSECONDS)) {
                    "Votifier network event loops did not terminate"
                }
            }
        }
        failure?.let { error ->
            if (interrupted) Thread.currentThread().interrupt()
            throw error
        }
    }

    private fun stopScheduler() {
        schedulerExecutor.shutdownNow()
        try {
            check(schedulerExecutor.awaitTermination(SCHEDULER_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                "Votifier scheduler did not terminate after interruption"
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while stopping the Votifier scheduler", error)
        }
    }

    private companion object {
        const val BOOTSTRAP_SHUTDOWN_SECONDS = 5L
        const val LIFECYCLE_QUIESCENCE_SECONDS = 5L
        const val SCHEDULER_SHUTDOWN_SECONDS = 5L

        // NuVotifier's public shutdown blocks uninterruptibly while closing the channel, then
        // initiates but does not await its private event loops. Keep handles to both so teardown
        // remains bounded and the module classloader is not released while callbacks remain.
        val SERVER_CHANNEL_FIELD: Field =
            VotifierServerBootstrap::class.java.getDeclaredField("serverChannel").apply {
                check(Channel::class.java.isAssignableFrom(type)) {
                    "Votifier serverChannel field has an incompatible type: ${type.name}"
                }
                check(trySetAccessible()) { "Votifier serverChannel field is inaccessible" }
            }

        val EVENT_LOOP_FIELDS: List<Field> =
            listOf("bossLoopGroup", "eventLoopGroup").map { name ->
                VotifierServerBootstrap::class.java.getDeclaredField(name).apply {
                    check(EventLoopGroup::class.java.isAssignableFrom(type)) {
                        "Votifier $name field has an incompatible type: ${type.name}"
                    }
                    check(trySetAccessible()) { "Votifier $name field is inaccessible" }
                }
            }

        fun votifierEventLoops(server: VotifierServerBootstrap): List<EventLoopGroup> =
            EVENT_LOOP_FIELDS
                .map { field -> field.get(server) as EventLoopGroup }
                .distinct()

        fun votifierServerChannel(server: VotifierServerBootstrap): Channel? = SERVER_CHANNEL_FIELD.get(server) as? Channel
    }
}

private fun expirePending(
    future: CompletableFuture<*>,
    error: Throwable,
): Boolean =
    synchronized(future) {
        !future.isDone && future.completeExceptionally(error)
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
