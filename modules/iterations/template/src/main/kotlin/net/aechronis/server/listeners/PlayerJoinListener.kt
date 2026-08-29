package net.aechronis.server.listeners

import net.aechronis.nodes.objects.Town
import net.aechronis.server.modules.ModuleContext
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import java.net.URI

object PlayerJoinListener {
    private val baseResourcePack =
        ResourcePackInfo
            .resourcePackInfo()
            .uri(URI("https://cdn.modrinth.com/data/LSmohupN/versions/zewiXtmr/Ashen_16x.zip"))
            .hash("d312836c38143301b7ba6a1247372b3f467116db")
            .build()

    private fun onAsyncPlayerConfiguration(
        event: AsyncPlayerConfigurationEvent,
        context: ModuleContext,
    ) {
        val player = event.player

        event.spawningInstance = context.instance
        player.respawnPoint = Town.fromPlayer(player)?.spawnpoint ?: context.spawnPoint
        player.gameMode = GameMode.SURVIVAL
        sendBasePack(player)
    }

    private fun sendBasePack(player: Player) {
        player.sendResourcePacks(
            ResourcePackRequest
                .resourcePackRequest()
                .packs(baseResourcePack)
                .prompt(Component.text("A resource pack is required to play"))
                .required(true)
                .build(),
        )
    }

    fun init(context: ModuleContext) {
        context.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            onAsyncPlayerConfiguration(event, context)
        }
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(::sendBasePack)
    }

    fun shutdown() {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            player.removeResourcePacks(baseResourcePack)
        }
    }
}
