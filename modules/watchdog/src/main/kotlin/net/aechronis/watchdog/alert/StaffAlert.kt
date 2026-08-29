package net.aechronis.watchdog.alert

import net.aechronis.utils.hasPermission
import net.aechronis.watchdog.WatchdogConfig
import net.aechronis.watchdog.objects.Flag
import net.aechronis.watchdog.objects.TranslationProbe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class StaffAlert(
    private val config: WatchdogConfig,
) {
    private val enabled = ConcurrentHashMap<UUID, Boolean>()

    fun toggle(player: Player): Boolean {
        val newValue = !(enabled[player.uuid] ?: true)
        enabled[player.uuid] = newValue
        return newValue
    }

    fun isEnabled(player: Player): Boolean = enabled[player.uuid] ?: true

    fun forget(player: Player) {
        enabled.remove(player.uuid)
    }

    fun clear() = enabled.clear()

    fun notify(flag: Flag) {
        val target = MinecraftServer.getConnectionManager().onlinePlayers.firstOrNull { it.uuid == flag.playerId }
        val targetName = target?.username ?: flag.playerId.toString()
        val message =
            Component
                .text(
                    "Watchdog: $targetName flagged ${flag.type} (${"%.2f".format(flag.certainty)})",
                    NamedTextColor.YELLOW,
                ).hoverEvent(HoverEvent.showText(Component.text(flag.details, NamedTextColor.RED)))
        sendToStaff(message)
    }

    fun notify(
        player: Player,
        result: TranslationProbe,
    ) {
        val base =
            Component.text(
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
        sendToStaff(message)
    }

    private fun sendToStaff(message: Component) {
        MinecraftServer
            .getConnectionManager()
            .onlinePlayers
            .filter { it.hasPermission(config.staffAlertPermission) && isEnabled(it) }
            .forEach { it.sendMessage(message) }
    }
}
