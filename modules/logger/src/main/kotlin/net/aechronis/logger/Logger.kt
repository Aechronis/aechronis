package net.aechronis.logger

import net.aechronis.logger.commands.LoggerCommand
import net.aechronis.logger.db.BlockLogRepository
import net.aechronis.logger.db.Database
import net.aechronis.logger.db.FeatureLogRepository
import net.aechronis.logger.db.InventorySnapshotRepository
import net.aechronis.logger.db.RollbackRepository
import net.aechronis.logger.db.StorageChangeRepository
import net.aechronis.logger.listeners.BlockListener
import net.aechronis.logger.listeners.EntityListener
import net.aechronis.logger.objects.FeatureLogEntry
import net.aechronis.logger.params.FeatureSourceRegistry
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode
import java.util.concurrent.CompletableFuture

object Logger {
    lateinit var repository: BlockLogRepository
    lateinit var featureRepository: FeatureLogRepository
    lateinit var rollbackRepository: RollbackRepository
    lateinit var storageChangeRepository: StorageChangeRepository
    lateinit var inventorySnapshotRepository: InventorySnapshotRepository

    private var initialized = false

    val eventNode = EventNode.all("logger")

    fun init(config: LoggerConfig) {
        val timeStart = System.currentTimeMillis()

        val database = Database(config)
        database.create()
        database.migrateBlockLog()
        database.createFeatureLog()
        database.migrateFeatureLog()
        database.createRollbackTables()
        database.createStorageChangeLog()
        database.createInventorySnapshotLog()
        database.migrateInventorySnapshotLog()

        repository = BlockLogRepository(database)
        featureRepository = FeatureLogRepository(database)
        rollbackRepository = RollbackRepository(database)
        storageChangeRepository = StorageChangeRepository(database)
        inventorySnapshotRepository = InventorySnapshotRepository(database)

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        BlockListener.init()
        EntityListener.init()

        MinecraftServer.getCommandManager().register(LoggerCommand())

        initialized = true

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Logger enabled in ${timeLoad}ms")
    }

    fun log(entry: FeatureLogEntry): CompletableFuture<Void> {
        check(initialized) { "Logger.log() was called before Logger.init(config)" }

        FeatureSourceRegistry.record(entry.source, entry.action)

        return featureRepository.insertAsync(entry).exceptionally { exception ->
            println("[Logger] failed to record feature log entry (source=${entry.source}): $exception")
            null
        }
    }
}
