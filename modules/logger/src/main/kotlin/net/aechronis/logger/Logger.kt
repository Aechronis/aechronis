package net.aechronis.logger

import net.aechronis.logger.commands.LoggerCommand
import net.aechronis.logger.db.BlockLogRepository
import net.aechronis.logger.db.Database
import net.aechronis.logger.db.RollbackRepository
import net.aechronis.logger.listeners.BlockListener
import net.minestom.server.MinecraftServer
import net.minestom.server.event.EventNode

object Logger {
    lateinit var repository: BlockLogRepository
    lateinit var rollbackRepository: RollbackRepository

    val eventNode = EventNode.all("logger")

    fun init(config: LoggerConfig) {
        val timeStart = System.currentTimeMillis()

        val database = Database(config)
        database.create()
        database.migrateBlockLog()
        database.createRollbackTables()

        repository = BlockLogRepository(database)
        rollbackRepository = RollbackRepository(database)

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        BlockListener.init()

        MinecraftServer.getCommandManager().register(LoggerCommand())

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Logger enabled in ${timeLoad}ms")
    }
}
