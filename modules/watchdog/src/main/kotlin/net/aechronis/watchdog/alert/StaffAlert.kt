package net.aechronis.watchdog.alert

import net.aechronis.utils.hasPermission
import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.TranslationProbe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player

internal class StaffAlert(
    private val config: WatchdogConfig,
) {
    fun notify(
        player: Player,
        result: TranslationProbe,
    ) {
        val base =
            Component
                .text(
                    "Watchdog: ${player.username} loaded with client ${config.clientLabel(player)}",
                    NamedTextColor.YELLOW,
                )
        val message =
            if (result.forbiddenKeys.isEmpty()) {
                base
            } else {
                base.hoverEvent(
                    HoverEvent.showText(
                        Component.text(
                            "Forbidden translation keys:\n${result.forbiddenKeys.joinToString("\n")}",
                            NamedTextColor.RED,
                        ),
                    ),
                )
            }
        MinecraftServer
            .getConnectionManager()
            .onlinePlayers
            .filter { it.hasPermission(config.staffAlertPermission) }
            .forEach { it.sendMessage(message) }
    }
}
