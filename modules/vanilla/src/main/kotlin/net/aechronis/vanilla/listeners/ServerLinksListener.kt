package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.network.packet.server.common.ServerLinksPacket

object ServerLinksListener {
    fun onSpawn(event: PlayerSpawnEvent) {
        if (!event.isFirstSpawn) return

        val entries =
            Vanilla.config.serverLinks.map { (title, url) ->
                ServerLinksPacket.Entry(Component.text(title), url)
            }
        event.player.sendPacket(ServerLinksPacket(entries))
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, ServerLinksListener::onSpawn)
    }
}
