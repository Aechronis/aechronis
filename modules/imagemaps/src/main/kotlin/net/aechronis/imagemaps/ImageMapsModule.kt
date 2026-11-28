package net.aechronis.imagemaps

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.utils.Command
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Path

class ImageMapsModule : AechronisModule {
    override val id = "imagemaps"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        ImageMaps.init(Path.of("imagemaps"))
        ImagesMenu.init(context)
        context.addListener(PlayerDisconnectEvent::class.java) { ImageMaps.forget(it.player) }
        // Maps reach inventories by pickup, chests and trades; rescanning is cheaper than hooking each path.
        ModuleScheduler
            .buildTask { MinecraftServer.getConnectionManager().onlinePlayers.forEach(ImageMaps::sendInventory) }
            .repeat(TaskSchedule.seconds(1))
            .schedule()
        // No permission: like /flags, anyone can browse and take any image.
        val gallery = Command("images").apply { setDefaultExecutor { player, _ -> ImagesMenu.open(player) } }
        ModuleCommands.register(ImageCommand(), ImageAdminCommand(), gallery)
    }

    override fun shutdown(context: ModuleContext) {
        ImagesMenu.closeAll()
        ImageMaps.shutdown()
    }
}
