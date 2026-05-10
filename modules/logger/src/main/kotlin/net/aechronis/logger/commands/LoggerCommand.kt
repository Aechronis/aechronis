package net.aechronis.logger.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import java.util.UUID

val playerInspectMode = HashMap<UUID, Boolean>()

class LoggerCommand : Command("logger", "logger", "lo") {
    init {
        setDefaultExecutor { sender, _ ->
            sender.sendMessage(
                Component.text("Usage: /logger inspect", NamedTextColor.GOLD),
            )
        }

        addSubcommand(LoggerInspectCommand())
    }
}

class LoggerInspectCommand : Command("inspect", "logger","i") {
    init {
        addSyntax({ sender: Player, _ ->
            playerInspectMode[sender.uuid] = !(playerInspectMode[sender.uuid] ?: false)

            sender.sendMessage(Component.text("Inspect mode ${if (playerInspectMode[sender.uuid]!!) "enabled" else "disabled"}.", NamedTextColor.GOLD))
        })
    }
}
