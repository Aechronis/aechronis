package net.aechronis.aechronis.listeners

import net.aechronis.aechronis.Aechronis
import net.aechronis.nodes.Nodes
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
    private fun onAsyncPlayerConfiguration(event: AsyncPlayerConfigurationEvent) {
        val player = event.player

        event.spawningInstance = Aechronis.instance
        player.respawnPoint = Nodes.getTownFromPlayer(player)?.spawnpoint ?: Pos(0.0, 64.0, 0.0)
        player.gameMode = GameMode.SURVIVAL

        // set packs
        val ashen =
            ResourcePackInfo
                .resourcePackInfo()
                .uri(URI("https://cdn.modrinth.com/data/LSmohupN/versions/9l7XdWVM/Ashen_16x.zip"))
                .hash("cfe67709602dd99461b8e0720f4b81d6ba44a8e7")
                .build()

        val aechronis =
            ResourcePackInfo
                .resourcePackInfo()
                .uri(URI("https://github.com/Aechronis/resource-pack/releases/download/56c1679/56c1679.zip"))
                .hash("80C400EA8EBFF9B394A4F90B6248E075D091A890")
                .build()

        player.sendResourcePacks(
            ResourcePackRequest
                .resourcePackRequest()
                .packs(ashen, aechronis)
                .prompt(Component.text("A resource pack is required to play"))
                .required(true)
                .build(),
        )
    }

    private fun onPlayerJoin(event: PlayerSpawnEvent) {
        val player = event.player

        player.sendPacket(UpdateViewDistancePacket(32))
        player.sendPacket(UpdateSimulationDistancePacket(32))
    }

    fun init() {
        Aechronis.eventNode.addListener(AsyncPlayerConfigurationEvent::class.java, PlayerJoinListener::onAsyncPlayerConfiguration)
        Aechronis.eventNode.addListener(PlayerSpawnEvent::class.java, PlayerJoinListener::onPlayerJoin)
    }
}
