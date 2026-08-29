package net.aechronis.logger

import net.aechronis.logger.commands.LoggerCommand
import net.aechronis.logger.commands.playerInspectMode
import net.aechronis.logger.db.Database
import net.aechronis.logger.listeners.BlockListener
import net.aechronis.logger.listeners.CombatExplosionListener
import net.aechronis.logger.listeners.EntityChangeListener
import net.aechronis.logger.listeners.EntityListener
import net.aechronis.logger.listeners.InventoryListener
import net.aechronis.logger.listeners.InventorySnapshotListener
import net.aechronis.logger.listeners.LastInteractionTracker
import net.aechronis.logger.listeners.LootListener
import net.aechronis.logger.listeners.WorldEditListener
import net.aechronis.logger.objects.FeatureLogEntry
import net.aechronis.logger.objects.OriginalChunkService
import net.aechronis.logger.objects.PendingRollbackRegistry
import net.aechronis.logger.objects.RollbackSafety
import net.aechronis.logger.objects.RollbackService
import net.aechronis.logger.objects.SnapshotViewer
import net.aechronis.logger.objects.VanillaStorage
import net.aechronis.logger.params.FeatureSourceRegistry
import net.aechronis.logger.repos.BlockLog
import net.aechronis.logger.repos.EntityChange
import net.aechronis.logger.repos.FeatureLog
import net.aechronis.logger.repos.InventoryChange
import net.aechronis.logger.repos.InventorySnapshot
import net.aechronis.logger.repos.Rollback
import net.aechronis.logger.repos.StorageChange
import net.aechronis.logger.utils.awaitLifecycleFuture
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDisconnectEvent
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

object Logger {
    lateinit var repository: BlockLog
    lateinit var featureLog: FeatureLog
    lateinit var rollback: Rollback
    lateinit var storageChange: StorageChange
    lateinit var inventorySnapshot: InventorySnapshot
    lateinit var inventoryChange: InventoryChange
    lateinit var entityChange: EntityChange
    lateinit var rollbackService: RollbackService
    lateinit var originalChunkService: OriginalChunkService
    lateinit var config: LoggerConfig

    private lateinit var database: Database
    private val resources = mutableListOf<AutoCloseable>()
    private var command: LoggerCommand? = null
    private var eventNodeRegistered = false

    @Volatile
    private var active = false

    @Volatile
    private var initialized = false

    @Volatile
    private var hasInitialized = false

    val isAcceptingLogs: Boolean get() = initialized

    lateinit var eventNode: EventNode<net.minestom.server.event.Event>
        private set

    @Synchronized
    fun init(config: LoggerConfig) {
        check(!active) { "Logger is already initialized" }
        active = true

        try {
            val timeStart = System.currentTimeMillis()
            Logger.config = config
            eventNode = EventNode.all("logger").setPriority(-50)
            resources.clear()
            database = Database(config)
            resources += database
            database.create()
            database.migrateBlockLog()
            database.createFeatureLog()
            database.migrateFeatureLog()
            database.createRollbackTables()
            database.createStorageChangeLog()
            database.migrateStorageChangeLog()
            database.createInventorySnapshotLog()
            database.migrateInventorySnapshotLog()
            database.createInventoryChangeLog()
            database.createEntityChangeLog()

            repository = BlockLog(database)
            resources += repository
            featureLog = FeatureLog(database)
            resources += featureLog
            rollback = Rollback(database)
            resources += rollback
            val interruptedOperations =
                awaitLifecycleFuture(
                    rollback.markInterruptedOperationsAsync(),
                    "interrupted rollback recovery scan",
                )
            if (interruptedOperations > 0) {
                println("[Logger] $interruptedOperations interrupted rollback operation(s) require recovery")
            }
            storageChange = StorageChange(database)
            resources += storageChange
            inventorySnapshot = InventorySnapshot(database)
            resources += inventorySnapshot
            inventoryChange = InventoryChange(database)
            resources += inventoryChange
            entityChange = EntityChange(database)
            resources += entityChange
            rollbackService = RollbackService()
            resources += rollbackService
            originalChunkService = OriginalChunkService(Path.of(config.originalWorldPath))
            resources += originalChunkService

            BlockListener.init()
            CombatExplosionListener.init()
            WorldEditListener.init()
            EntityListener.init()
            EntityChangeListener.init()
            InventoryListener.init()
            InventorySnapshotListener.init()
            LootListener.init()
            SnapshotViewer.init()
            VanillaStorage.init()
            eventNode.addListener(PlayerDisconnectEvent::class.java) { event ->
                RollbackSafety.clear(event.player.uuid)
                PendingRollbackRegistry.clearPlayer(event.player.uuid)
            }
            ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
            eventNodeRegistered = true

            command = LoggerCommand()
            MinecraftServer.getCommandManager().register(command!!)

            initialized = true
            hasInitialized = true
            println("Logger enabled in ${System.currentTimeMillis() - timeStart}ms")
        } catch (error: Throwable) {
            runCatching(::close).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    fun log(entry: FeatureLogEntry): CompletableFuture<Void> {
        if (!initialized) {
            check(hasInitialized) { "Logger.log() was called before Logger.init(config)" }
            // Events can still be unwinding while the server is shutting down. They must not
            // submit work to repositories whose executors are being closed.
            return CompletableFuture.completedFuture(null)
        }

        FeatureSourceRegistry.record(entry.source, entry.action)

        return featureLog.insertAsync(entry).exceptionally { exception ->
            println("[Logger] failed to record feature log entry (source=${entry.source}): $exception")
            null
        }
    }

    @Synchronized
    fun close() {
        if (!active) return
        initialized = false

        // The normal server shutdown order has already dispatched player disconnect events. Remove
        // every logger event node before closing executors as a final guard against late dispatch.
        var failure: Throwable? = null

        fun cleanup(action: () -> Unit): Boolean =
            try {
                action()
                true
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
                false
            }

        if (eventNodeRegistered && cleanup { MinecraftServer.getGlobalEventHandler().removeChild(eventNode) }) {
            eventNodeRegistered = false
        }
        command?.let { registered ->
            if (cleanup { MinecraftServer.getCommandManager().unregister(registered) }) command = null
        }
        cleanup(BlockListener::close)
        cleanup(LootListener::close)
        cleanup(SnapshotViewer::close)
        cleanup(VanillaStorage::close)

        val reverseResources = resources.asReversed().toList()
        var firstUnclosed = reverseResources.size
        for ((index, resource) in reverseResources.withIndex()) {
            if (!cleanup(resource::close)) {
                firstUnclosed = index
                break
            }
        }
        resources.clear()
        if (firstUnclosed < reverseResources.size) {
            resources += reverseResources.drop(firstUnclosed).asReversed()
        }

        failure?.let { throw it }
        active = false
        PendingRollbackRegistry.clear()
        RollbackSafety.clear()
        LastInteractionTracker.clear()
        FeatureSourceRegistry.clear()
        playerInspectMode.clear()
    }
}
