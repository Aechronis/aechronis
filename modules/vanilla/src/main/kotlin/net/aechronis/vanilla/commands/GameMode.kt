package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player

class GameMode : Command("gamemode", "vanilla.gamemode") {
    init {
        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/gamemode <gamemode>")
            Message.print(player, "/gamemode <gamemode> <player>")
        }

        val playerArg = ArgumentType.Entity("player-name").singleEntity(true).onlyPlayers(true)
        val gameModeArg = ArgumentType.Enum("gamemode", GameMode::class.java)

        addSyntax({ sender: Player, context ->
            val player =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }

            player.gameMode = context[gameModeArg]
        }, gameModeArg, playerArg)

        addSyntax({ sender: Player, context ->
            sender.gameMode = context[gameModeArg]
        }, gameModeArg)
    }
}
