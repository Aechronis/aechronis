package net.aechronis.watchdog.commands

import net.aechronis.utils.Command
import net.aechronis.watchdog.Watchdog
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class WatchdogCommand(
    permission: String,
) : Command("watchdog", permission) {
    init {
        setDefaultExecutor { player, _ ->
            player.sendMessage(Component.text("Usage: /watchdog alerts", NamedTextColor.YELLOW))
        }
        addSyntax(
            { player: Player, _ -> toggleAlerts(player) },
            ArgumentType.Literal("alerts"),
        )
    }

    private fun toggleAlerts(player: Player) {
        val enabled = Watchdog.toggleAlerts(player)
        val state = if (enabled) "enabled" else "disabled"
        player.sendMessage(Component.text("Watchdog alerts $state.", NamedTextColor.YELLOW))
    }
}
