package net.aechronis.logger.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val playerInspectMode = ConcurrentHashMap<UUID, Boolean>()

class LoggerInspectCommand : Command("inspect", "logger.inspect", "i") {
    init {
        addSyntax({ sender: Player, _ ->
            val enabled = playerInspectMode.compute(sender.uuid) { _, current -> current != true } == true
            sender.sendMessage(Component.text("Inspect mode ${if (enabled) "enabled" else "disabled"}.", NamedTextColor.GOLD))
        })
    }
}
