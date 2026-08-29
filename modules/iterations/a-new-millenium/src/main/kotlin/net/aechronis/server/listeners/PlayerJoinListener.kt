package net.aechronis.server.listeners

import net.aechronis.nodes.objects.Town
import net.aechronis.server.modules.ModuleContext
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent

object PlayerJoinListener {
    private fun onAsyncPlayerConfiguration(
        event: AsyncPlayerConfigurationEvent,
        context: ModuleContext,
    ) {
        val player = event.player

        event.spawningInstance = context.instance
        player.respawnPoint = Town.fromPlayer(player)?.spawnpoint ?: context.spawnPoint
        player.gameMode = GameMode.SURVIVAL
    }

    fun init(context: ModuleContext) {
        context.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            onAsyncPlayerConfiguration(event, context)
        }
    }
}
