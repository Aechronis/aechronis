package net.aechronis.aechronis.listeners

import net.aechronis.aechronis.Aechronis
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.event.server.ServerListPingEvent
import net.minestom.server.ping.Status

object ServerListPingListener {
    private fun onServerListPing(event: ServerListPingEvent) {
        val inputStream =
            object {}
                .javaClass.classLoader
                .getResourceAsStream("favicon.png")

        val favicon: ByteArray = inputStream?.readBytes() ?: return

        event.setStatus(
            Status
                .builder()
                .description(
                    Component
                        .text("                 Aechronis", NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true)
                        .appendNewline()
                        .append(
                            Component
                                .text(
                                    "                 Template iteration",
                                    NamedTextColor.GRAY,
                                ).decoration(TextDecoration.BOLD, false),
                        ),
                ).favicon(favicon)
                .playerInfo(event.status.playerInfo)
                .versionInfo(event.status.versionInfo)
                .build(),
        )
    }

    fun init() {
        Aechronis.eventNode.addListener(ServerListPingEvent::class.java, ServerListPingListener::onServerListPing)
    }
}
