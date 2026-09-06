package net.aechronis.craftingstore

import com.google.gson.GsonBuilder
import net.craftingstore.core.CraftingStore
import net.craftingstore.core.CraftingStorePlugin
import net.craftingstore.core.PluginConfiguration
import net.craftingstore.core.logging.CraftingStoreLogger
import net.craftingstore.core.models.donation.Donation
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.timer.TaskSchedule
import java.lang.reflect.Field
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger

internal class CraftingStorePluginAdapter(
    private val options: CraftingStoreOptions,
    private val config: ConfigStore,
    val eventNode: EventNode<net.minestom.server.event.Event>,
) : CraftingStorePlugin {
    private val logger = StoreLogger(options.logger)
    private val scheduler: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2) { task ->
            Thread(task, "craftingstore-scheduler").also { it.isDaemon = true }
        }
    private val scheduled = Collections.synchronizedSet(mutableSetOf<ScheduledFuture<*>>())
    private val stopped = AtomicBoolean()
    private val lifecycleLock = ReentrantLock()
    private var command: Command? = null
    private var lifecycleQuiesced = false
    private var storeDisabled = false
    private var donationExecutorStopped = false
    private var storeExecutor: ExecutorService? = null
    private var schedulerStopped = false
    val placeholders = CraftingStorePlaceholders()
    private val pluginConfiguration =
        object : PluginConfiguration {
            override fun getName() = "CraftingStore"

            override fun getMainCommands() = arrayOf("craftingstore")

            override fun getVersion() = options.upstreamVersion

            override fun getPlatform() = "Minestom"

            override fun isBuyCommandEnabled() = !config.current().disableBuyCommand

            override fun getTimeBetweenCommands() = config.current().timeBetweenCommandsMillis.toInt()

            override fun getNotEnoughBalanceMessage() = config.current().notEnoughBalanceMessage

            override fun isUsingAlternativeApi() = config.current().useAlternativeApi
        }

    lateinit var store: CraftingStore
        private set

    fun prepareEventListeners() {
        check(!stopped.get()) { "CraftingStore is shut down" }
        check(!::store.isInitialized) { "CraftingStore event listeners are already prepared" }
        logger.setDebugging(config.current().debug)
        store = CraftingStore(this)
        storeExecutor = craftingStoreExecutor(store)
        registerListeners()
    }

    fun start() {
        check(!stopped.get()) { "CraftingStore is shut down" }
        check(::store.isInitialized) { "CraftingStore event listeners have not been prepared" }
        registerCommands()
    }

    override fun executeDonation(donation: Donation): Boolean {
        if (stopped.get()) return false
        if (donation.player.isRequiredOnline() && onlinePlayer(donation) == null) return false
        val result =
            onTick {
                if (donation.player.isRequiredOnline() && onlinePlayer(donation) == null) return@onTick false
                val event = DonationReceivedEvent(donation)
                eventNode.call(event)
                if (event.isCancelled) {
                    logger.debug("Donation command ${donation.commandId} was cancelled")
                    return@onTick false
                }
                val command =
                    donation.command
                        .trim()
                        .removePrefix("/")
                        .trim()
                if (command.isEmpty()) return@onTick false
                val commandResult = MinecraftServer.getCommandManager().executeServerCommand(command)
                if (commandResult.type != net.minestom.server.command.builder.CommandResult.Type.SUCCESS) {
                    logger.error("Donation command ${donation.commandId} did not succeed: ${commandResult.type}")
                    false
                } else {
                    true
                }
            }
        return try {
            result.get(15, TimeUnit.SECONDS)
        } catch (exception: TimeoutException) {
            if (!expirePending(result, exception)) return runCatching(result::join).getOrDefault(false)
            logger.error("Donation command ${donation.commandId} timed out before tick dispatch")
            false
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!expirePending(result, exception)) return runCatching(result::join).getOrDefault(false)
            logger.error("Donation command ${donation.commandId} was interrupted before tick dispatch")
            false
        } catch (exception: Exception) {
            logger.error("Donation command ${donation.commandId} timed out or failed: ${exception.message}")
            false
        }
    }

    override fun getLogger(): CraftingStoreLogger = logger

    override fun registerRunnable(
        runnable: Runnable,
        delay: Int,
        interval: Int,
    ) {
        if (stopped.get()) return
        val future = scheduler.scheduleAtFixedRate(runnable, delay.toLong(), interval.toLong().coerceAtLeast(1), TimeUnit.SECONDS)
        scheduled += future
    }

    override fun runAsyncTask(runnable: Runnable) {
        if (!stopped.get()) scheduler.execute(runnable)
    }

    override fun getToken(): String = config.effectiveApiKey()

    override fun getConfiguration(): PluginConfiguration = pluginConfiguration

    fun reloadFromDisk(): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        runAsyncTask {
            try {
                config.reload()
                logger.setDebugging(config.current().debug)
                result.complete(store.reload().get())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result
    }

    fun saveKey(key: String): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        runAsyncTask {
            try {
                config.update { it.apiKey = key }
                logger.setDebugging(config.current().debug)
                result.complete(store.reload().get())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return result
    }

    fun setDebug(debug: Boolean) {
        config.update { it.debug = debug }
        logger.setDebugging(debug)
    }

    private fun registerListeners() {
        eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            if (event.isFirstSpawn) store.executeQueue()
            if (event.isFirstSpawn && permission(event.player, store.ADMIN_PERMISSION)) {
                store.information
                    ?.updateInformation
                    ?.message
                    ?.let(event.player::sendMessage)
            }
        }
        eventNode.addListener(PlayerDisconnectEvent::class.java) { /* no menu/session to leak */ }
    }

    private fun registerCommands() {
        val manager = MinecraftServer.getCommandManager()
        check(!manager.commandExists("craftingstore")) { "Cannot register /craftingstore: command already exists" }
        command = AdminCommand()
        options.registerCommand(command!!)
    }

    private inner class AdminCommand : Command("craftingstore") {
        // Argument identifiers must be unique within a syntax. The literal's identifier is
        // "key", so the API-key value uses a distinct identifier to avoid Minestom failing
        // while it builds the parsed-arguments map.
        private val apiKey = ArgumentType.Word("api-key")
        private val value = ArgumentType.Word("value").from("true", "false")

        init {
            setDefaultExecutor { sender, _ -> help(sender) }
            addSyntax({ sender, _ -> reload(sender) }, ArgumentType.Literal("reload"))
            addSyntax({ sender, context -> setKey(sender, context[apiKey]) }, ArgumentType.Literal("key"), apiKey)
            addSyntax({ sender, _ -> debug(sender, null) }, ArgumentType.Literal("debug"))
            addSyntax({ sender, context -> debug(sender, context[value].toBoolean()) }, ArgumentType.Literal("debug"), value)
        }

        private fun allowed(sender: CommandSender): Boolean {
            if (sender !is Player) return true
            if (permission(sender, store.ADMIN_PERMISSION)) return true
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED))
            return false
        }

        private fun help(sender: CommandSender) {
            if (!allowed(sender)) return
            sender.sendMessage(Component.text("/craftingstore reload | key <key> | debug [true|false]", NamedTextColor.YELLOW))
        }

        private fun reload(sender: CommandSender) {
            if (!allowed(sender)) return
            try {
                val future = reloadFromDisk()
                future.whenComplete { success, error ->
                    val succeeded = error == null && success == true
                    onTick {
                        sender.sendMessage(
                            Component.text(
                                if (succeeded) "CraftingStore reloaded." else "CraftingStore reload failed.",
                                if (succeeded) NamedTextColor.GREEN else NamedTextColor.RED,
                            ),
                        )
                        Unit
                    }
                }
            } catch (error: Exception) {
                sender.sendMessage(Component.text("CraftingStore config could not be loaded: ${error.message}", NamedTextColor.RED))
            }
        }

        private fun setKey(
            sender: CommandSender,
            value: String,
        ) {
            if (!allowed(sender)) return
            if (value.isBlank()) {
                sender.sendMessage(Component.text("The API key cannot be blank.", NamedTextColor.RED))
                return
            }
            try {
                saveKey(value).whenComplete { success, error ->
                    val succeeded = error == null && success == true
                    onTick {
                        sender.sendMessage(
                            Component.text(
                                if (succeeded) "CraftingStore API key accepted." else "CraftingStore API key is invalid.",
                                if (succeeded) NamedTextColor.GREEN else NamedTextColor.RED,
                            ),
                        )
                        Unit
                    }
                }
            } catch (error: Exception) {
                sender.sendMessage(Component.text("Could not save CraftingStore config: ${error.message}", NamedTextColor.RED))
            }
        }

        private fun debug(
            sender: CommandSender,
            value: Boolean?,
        ) {
            if (!allowed(sender)) return
            if (value == null) {
                sender.sendMessage(Component.text("CraftingStore debug: ${config.current().debug}", NamedTextColor.YELLOW))
            } else {
                setDebug(value)
                sender.sendMessage(Component.text("CraftingStore debug set to $value.", NamedTextColor.GREEN))
            }
        }
    }

    private fun permission(
        player: Player,
        node: String,
    ) = options.permissionChecker(player, node)

    private fun onlinePlayer(donation: Donation): Player? =
        donation.player.uuid?.let(MinecraftServer.getConnectionManager()::getOnlinePlayerByUuid)
            ?: MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(donation.player.username)

    internal fun <T> onTick(block: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (stopped.get()) {
            future.completeExceptionally(IllegalStateException("CraftingStore is shut down"))
            return future
        }
        try {
            MinecraftServer.getSchedulerManager().submitTask {
                synchronized(future) {
                    if (!future.isDone) {
                        try {
                            lifecycleLock.lock()
                            try {
                                check(!stopped.get()) { "CraftingStore is shut down" }
                                future.complete(block())
                            } finally {
                                lifecycleLock.unlock()
                            }
                        } catch (error: Throwable) {
                            future.completeExceptionally(error)
                        }
                    }
                }
                TaskSchedule.stop()
            }
        } catch (error: Throwable) {
            future.completeExceptionally(error)
        }
        return future
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

        command?.let { registered ->
            cleanup {
                options.unregisterCommand(registered)
                command = null
            }
        }
        if (!lifecycleQuiesced) {
            cleanup {
                awaitLifecycleQuiescence()
                lifecycleQuiesced = true
            }
        }
        if (scheduled.isNotEmpty()) {
            cleanup {
                synchronized(scheduled) {
                    scheduled.forEach { it.cancel(false) }
                    scheduled.clear()
                }
            }
        }
        if (this::store.isInitialized) {
            if (!storeDisabled) {
                cleanup {
                    store.setEnabled(false)
                    storeDisabled = true
                }
            }
            if (!donationExecutorStopped) {
                cleanup {
                    stopExecutor(store.donationRunner.executor, "CraftingStore donation executor")
                    donationExecutorStopped = true
                }
            }
        }
        storeExecutor?.let { executor ->
            cleanup {
                stopExecutor(executor, "CraftingStore API executor")
                storeExecutor = null
            }
        }
        if (!schedulerStopped) {
            cleanup {
                stopExecutor(scheduler, "CraftingStore scheduler")
                schedulerStopped = true
            }
        }
        failure?.let { throw it }
    }

    private fun awaitLifecycleQuiescence() {
        try {
            check(lifecycleLock.tryLock(LIFECYCLE_QUIESCENCE_SECONDS, TimeUnit.SECONDS)) {
                "CraftingStore lifecycle work did not quiesce"
            }
            lifecycleLock.unlock()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while quiescing CraftingStore lifecycle work", error)
        }
    }

    private fun stopExecutor(
        executor: ExecutorService,
        description: String,
    ) {
        executor.shutdownNow()
        try {
            check(executor.awaitTermination(EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                "$description did not terminate after interruption"
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while stopping $description", error)
        }
    }

    private companion object {
        const val EXECUTOR_SHUTDOWN_SECONDS = 5L
        const val LIFECYCLE_QUIESCENCE_SECONDS = 5L

        // CraftingStore exposes its donation executor but not the separate executor used by
        // reload/API work. Keep an explicit handle so a runtime module unload cannot leak it.
        val STORE_EXECUTOR_FIELD: Field =
            CraftingStore::class.java.getDeclaredField("executor").apply {
                check(ExecutorService::class.java.isAssignableFrom(type)) {
                    "CraftingStore executor field has an incompatible type: ${type.name}"
                }
                check(trySetAccessible()) { "CraftingStore executor field is inaccessible" }
            }

        fun craftingStoreExecutor(store: CraftingStore): ExecutorService =
            STORE_EXECUTOR_FIELD.get(store) as? ExecutorService
                ?: error("CraftingStore executor is not initialized")
    }
}

private fun expirePending(
    future: CompletableFuture<*>,
    error: Throwable,
): Boolean =
    synchronized(future) {
        !future.isDone && future.completeExceptionally(error)
    }

private class StoreLogger(
    private val target: Logger,
) : CraftingStoreLogger() {
    override fun info(message: String) = target.info(message)

    override fun error(message: String) = target.log(Level.SEVERE, message)
}

internal class ConfigStore(
    private val directory: Path,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = directory.resolve("config.json")
    private val lock = Any()

    @Volatile private var config = CraftingStoreFileConfig()

    fun current(): CraftingStoreFileConfig =
        synchronized(lock) {
            gson.fromJson(gson.toJson(config), CraftingStoreFileConfig::class.java).normalized()
        }

    fun effectiveApiKey(): String = System.getenv("CRAFTINGSTORE_API_KEY")?.takeIf { it.isNotBlank() } ?: current().apiKey

    fun reload() =
        synchronized(lock) {
            Files.createDirectories(directory)
            if (!Files.exists(file)) {
                config = CraftingStoreFileConfig()
                saveLocked()
                return@synchronized
            }
            val parsed =
                gson.fromJson(Files.readString(file), CraftingStoreFileConfig::class.java)
                    ?: error("CraftingStore config is empty")
            config = parsed.normalized()
        }

    fun update(change: (CraftingStoreFileConfig) -> Unit) =
        synchronized(lock) {
            change(config)
            config.normalized()
            Files.createDirectories(directory)
            saveLocked()
        }

    private fun saveLocked() {
        val temporary = file.resolveSibling("config.json.tmp")
        Files.writeString(temporary, gson.toJson(config))
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (
            _: Exception,
        ) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
