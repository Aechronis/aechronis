package net.aechronis.watchdog

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class WatchdogModule : AechronisModule {
    override val id = "watchdog"
    override val dependencies = setOf("utils")

    override fun initialize(context: ModuleContext) = Watchdog.initialize()

    override fun shutdown(context: ModuleContext) = Watchdog.shutdown()
}
