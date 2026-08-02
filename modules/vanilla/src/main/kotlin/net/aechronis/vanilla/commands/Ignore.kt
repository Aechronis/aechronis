package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Ignore : Command("ignore", "vanilla.ignore") {
    private val playerArg = ArgumentType.Entity("player").singleEntity(true).onlyPlayers(true)

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/ignore <player>", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                    return@addSyntax
                }

            if (target.uuid == sender.uuid) {
                sender.sendMessage(Component.text("You can't ignore yourself.", NamedTextColor.RED))
                return@addSyntax
            }

            val set = Commands.getIgnored(sender)
            if (set.remove(target.uuid)) {
                sender.sendMessage(Component.text("You are no longer ignoring ${target.username}.", NamedTextColor.LIGHT_PURPLE))
            } else {
                set.add(target.uuid)
                sender.sendMessage(Component.text("You are now ignoring ${target.username}.", NamedTextColor.LIGHT_PURPLE))
            }
        }, playerArg)
    }
}
