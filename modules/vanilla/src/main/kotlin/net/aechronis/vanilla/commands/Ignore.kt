package net.aechronis.vanilla.commands

import net.aechronis.vanilla.managers.Commands
import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Ignore : Command("ignore", "vanilla.ignore") {
    private val playerArg = ArgumentType.Entity("player").singleEntity(true).onlyPlayers(true)

    init {
        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/ignore <player>")
        }

        addSyntax({ sender: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }

            if (target.uuid == sender.uuid) {
                Message.error(sender, "You can't ignore yourself.")
                return@addSyntax
            }

            val set = Commands.getIgnored(sender)
            if (set.remove(target.uuid)) {
                Message.print(sender, "You are no longer ignoring ${target.username}.")
            } else {
                set.add(target.uuid)
                Message.print(sender, "You are now ignoring ${target.username}.")
            }
        }, playerArg)
    }
}
