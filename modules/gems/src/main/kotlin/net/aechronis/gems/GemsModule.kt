package net.aechronis.gems

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent

class GemsModule : AechronisModule {
    override val id = "gems"
    override val dependencies = setOf("nodes", "utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        Gems.initialize()
        context.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            Gems.rememberPlayer(event.player)
        }
    }

    override fun shutdown(context: ModuleContext) = Gems.shutdown()
}
