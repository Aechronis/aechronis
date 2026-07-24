package net.aechronis.aechronis.listeners

import net.aechronis.aechronis.Aechronis
import net.aechronis.nodes.objects.Town
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.network.packet.server.play.UpdateSimulationDistancePacket
import net.minestom.server.network.packet.server.play.UpdateViewDistancePacket
import java.net.URI

object PlayerJoinListener {
    private val resourcePackRequest =
        ResourcePackRequest
            .resourcePackRequest()
            .packs(
                ResourcePackInfo
                    .resourcePackInfo()
                    .uri(URI("https://cdn.modrinth.com/data/LSmohupN/versions/znwxp1rV/Ashen_16x.zip"))
                    .hash("d7ab3cd0cc3b848942286ce608db615929e874ed")
                    .build(),
                ResourcePackInfo
                    .resourcePackInfo()
                    .uri(URI("https://github.com/Aechronis/resource-pack/releases/download/a-new-millenium-72aeecf/72aeecf.zip"))
                    .hash("76d06014c4251a9d87d61893bd92eafc3e9ee3ce1c5cb080f5e21cf0516dbefb")
                    .build(),
            ).prompt(Component.text("A resource pack is required to play"))
            .required(true)
            .build()

    private fun onAsyncPlayerConfiguration(event: AsyncPlayerConfigurationEvent) {
        val player = event.player

        event.spawningInstance = Aechronis.instance
        player.respawnPoint = Town.fromPlayer(player)?.spawnpoint ?: Pos(0.0, 65.0, 0.0)
        player.gameMode = GameMode.SURVIVAL
        player.sendResourcePacks(resourcePackRequest)
    }

    private fun onPlayerJoin(event: PlayerSpawnEvent) {
        val player = event.player
        player.sendPacket(UpdateViewDistancePacket(Aechronis.VIEW_DISTANCE))
        player.sendPacket(UpdateSimulationDistancePacket(Aechronis.VIEW_DISTANCE))
    }

    fun init() {
        Aechronis.eventNode.addListener(AsyncPlayerConfigurationEvent::class.java, PlayerJoinListener::onAsyncPlayerConfiguration)
        Aechronis.eventNode.addListener(PlayerSpawnEvent::class.java, PlayerJoinListener::onPlayerJoin)
    }
}
