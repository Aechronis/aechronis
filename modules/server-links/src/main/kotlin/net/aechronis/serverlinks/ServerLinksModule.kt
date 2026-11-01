package net.aechronis.serverlinks

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.kyori.adventure.text.Component
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.network.packet.server.common.ServerLinksPacket

class ServerLinksModule : AechronisModule {
    override val id = "server-links"

    override fun initialize(context: ModuleContext) {
        val entries =
            listOf(
                "Map" to "https://map.aechronis.net",
                "Website" to "https://aechronis.net",
                "Discord" to "https://discord.gg/aechronis",
                "Store" to "https://shop.aechronis.net",
            ).map { (title, url) -> ServerLinksPacket.Entry(Component.text(title), url) }
        context.addListener(PlayerSpawnEvent::class.java) { event ->
            if (event.isFirstSpawn) event.player.sendPacket(ServerLinksPacket(entries))
        }
    }
}
