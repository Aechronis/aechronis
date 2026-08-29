package net.aechronis.logger

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class LoggerModule : AechronisModule {
    override val id = "logger"
    override val dependencies = setOf("utils", "combat", "vanilla", "worldedit")

    override fun initialize(context: ModuleContext) = Logger.init(LoggerConfig(limit = 999999999))

    override fun prepareForShutdown(context: ModuleContext) = Logger.close()

    override fun shutdown(context: ModuleContext) = Logger.close()
}
