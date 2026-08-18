package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player

class Ignore : Command("ignore", "vanilla.ignore") {
    private val playerArg = PlayerTargets.argument()

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/ignore <player>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, context ->
            val targets =
                PlayerTargets
                    .resolve(sender, context[playerArg])
                    ?.filter { it.uuid != sender.uuid }
                    ?: return@addSyntax

            if (targets.isEmpty()) {
                sender.sendMessage(Component.text("You can't ignore yourself.", NamedTextColor.RED))
                return@addSyntax
            }

            val set = Commands.getIgnored(sender)
            var ignored = 0
            targets.forEach { target ->
                if (set.remove(target.uuid)) {
                    ignored++
                } else {
                    set.add(target.uuid)
                }
            }
            sender.sendMessage(
                Component.text(
                    "Toggled ignore for ${targets.size} player(s); $ignored no longer ignored.",
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
        }, playerArg)
    }
}
