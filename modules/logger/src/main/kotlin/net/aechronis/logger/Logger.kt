package net.aechronis.logger

import net.aechronis.logger.commands.LoggerCommand
import net.aechronis.logger.db.Database
import net.aechronis.logger.listeners.BlockListener
import net.aechronis.logger.listeners.CombatExplosionListener
import net.aechronis.logger.listeners.EntityChangeListener
import net.aechronis.logger.listeners.EntityListener
import net.aechronis.logger.listeners.InventoryListener
import net.aechronis.logger.listeners.InventorySnapshotListener
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

    @Volatile
    private var initialized = false

    @Volatile
    private var hasInitialized = false

    val isAcceptingLogs: Boolean get() = initialized

    val eventNode = EventNode.all("logger").setPriority(-50)

    fun init(config: LoggerConfig) {
        check(!initialized) { "Logger is already initialized" }

        val timeStart = System.currentTimeMillis()
        Logger.config = config
        database = Database(config)
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
        featureLog = FeatureLog(database)
        rollback = Rollback(database)
        val interruptedOperations = rollback.markInterruptedOperationsAsync().get()
        if (interruptedOperations > 0) {
            println("[Logger] $interruptedOperations interrupted rollback operation(s) require recovery")
        }
        storageChange = StorageChange(database)
        inventorySnapshot = InventorySnapshot(database)
        inventoryChange = InventoryChange(database)
        entityChange = EntityChange(database)
        rollbackService = RollbackService()
        originalChunkService = OriginalChunkService(Path.of(config.originalWorldPath))

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
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

        MinecraftServer.getCommandManager().register(LoggerCommand())

        initialized = true
        hasInitialized = true

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Logger enabled in ${timeLoad}ms")
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
        if (!initialized) return
        initialized = false

        // The normal server shutdown order has already dispatched player disconnect events. Remove
        // every logger event node before closing executors as a final guard against late dispatch.
        runCatching { MinecraftServer.getGlobalEventHandler().removeChild(eventNode) }
        BlockListener.close()
        LootListener.close()

        var failure: Exception? = null
        val resources =
            listOf(
                originalChunkService,
                rollbackService,
                repository,
                featureLog,
                rollback,
                storageChange,
                inventoryChange,
                entityChange,
                inventorySnapshot,
                database,
            )

        for (resource in resources) {
            try {
                resource.close()
            } catch (exception: Exception) {
                if (failure == null) {
                    failure = exception
                } else {
                    failure.addSuppressed(exception)
                }
            }
        }

        failure?.let { throw it }
    }
}
