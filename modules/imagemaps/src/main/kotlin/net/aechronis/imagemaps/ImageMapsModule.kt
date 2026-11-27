package net.aechronis.imagemaps

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Path

class ImageMapsModule : AechronisModule {
    override val id = "imagemaps"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        ImageMaps.init(Path.of("imagemaps"))
        context.addListener(PlayerDisconnectEvent::class.java) { ImageMaps.forget(it.player) }
        // Maps reach inventories by pickup, chests and trades; rescanning is cheaper than hooking each path.
        ModuleScheduler
            .buildTask { MinecraftServer.getConnectionManager().onlinePlayers.forEach(ImageMaps::sendInventory) }
            .repeat(TaskSchedule.seconds(1))
            .schedule()
        ModuleCommands.register(ImageCommand())
    }

    override fun shutdown(context: ModuleContext) = ImageMaps.shutdown()
}
