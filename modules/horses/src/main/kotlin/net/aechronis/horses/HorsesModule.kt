package net.aechronis.horses

import net.aechronis.horses.listeners.HorseListener
import net.aechronis.horses.managers.Horses
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class HorsesModule : AechronisModule {
    override val id = "horses"
    override val dependencies = setOf("vanilla")

    override fun initialize(context: ModuleContext) {
        Horses.init(Path.of("horses", "horses.json"), context.instance)
        HorseListener.init(context)
    }

    override fun prepareForShutdown(context: ModuleContext) {
        // Release riders before Vanilla saves player positions.
        context.captureLive(Horses::prepareForShutdown)
    }

    override fun saveCheckpoint(context: ModuleContext): CompletableFuture<Void> {
        context.captureLive(Horses::save)
        return CompletableFuture.completedFuture(null)
    }

    override fun saveState(context: ModuleContext) {
        saveCheckpoint(context).join()
    }

    override fun shutdown(context: ModuleContext) {
        context.captureLive(Horses::shutdown)
    }
}
