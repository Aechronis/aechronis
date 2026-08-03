package net.aechronis.server.listeners

import net.aechronis.nodes.objects.Town
import net.aechronis.server.Server
import net.aechronis.server.resourcepack.ResourcePackServer
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import java.net.URI

object PlayerJoinListener {
    private val baseResourcePack =
        ResourcePackInfo
            .resourcePackInfo()
            .uri(URI("https://cdn.modrinth.com/data/LSmohupN/versions/zewiXtmr/Ashen_16x.zip"))
            .hash("d312836c38143301b7ba6a1247372b3f467116db")
            .build()

    private lateinit var resourcePackServer: ResourcePackServer

    private fun onAsyncPlayerConfiguration(event: AsyncPlayerConfigurationEvent) {
        val player = event.player

        event.spawningInstance = Server.instance
        player.respawnPoint = Town.fromPlayer(player)?.spawnpoint ?: Pos(0.0, 64.0, 0.0)
        player.gameMode = GameMode.SURVIVAL
        player.sendResourcePacks(
            ResourcePackRequest
                .resourcePackRequest()
                .packs(
                    baseResourcePack,
                    resourcePackServer.resourcePackInfo(player.playerConnection.serverAddress),
                ).prompt(Component.text("A resource pack is required to play"))
                .required(true)
                .build(),
        )
    }

    fun init(resourcePackServer: ResourcePackServer) {
        this.resourcePackServer = resourcePackServer
        Server.eventNode.addListener(AsyncPlayerConfigurationEvent::class.java, PlayerJoinListener::onAsyncPlayerConfiguration)
    }
}
