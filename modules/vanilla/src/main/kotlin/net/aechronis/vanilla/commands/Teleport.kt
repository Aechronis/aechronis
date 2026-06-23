package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Command
import net.aechronis.vanilla.utils.Message
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Teleport : Command("teleport", "vanilla.teleport", "tp") {
    init {
        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage:")
            Message.print(player, "/teleport <playerA-name>")
            Message.print(player, "/teleport <position>")
            Message.print(player, "/teleport <playerA-name> <playerB-name>")
            Message.print(player, "/teleport <playerA-name> <position>")
        }

        val playerAArg = ArgumentType.Entity("playerA-name").singleEntity(true).onlyPlayers(true)
        val playerBArg = ArgumentType.Entity("playerB-name").singleEntity(true).onlyPlayers(true)
        val posArg = ArgumentType.RelativeVec3("position")

        // teleport self to other player
        addSyntax({ sender: Player, context ->
            val pos = context[playerAArg].findFirstPlayer(sender)?.position
            sender.teleport(pos)
        }, playerAArg)

        // teleport self to coords
        addSyntax({ sender: Player, context ->
            val pos = context[posArg].from(sender.position).asPos()
            sender.teleport(pos)
        }, posArg)

        // teleport player to other player
        addSyntax({ sender: Player, context ->
            val player = context[playerAArg].findFirstPlayer(sender)
            val pos = context[playerBArg].findFirstPlayer(sender)?.position
            player?.teleport(pos)
        }, playerAArg, playerBArg)

        // teleport player to coords
        addSyntax({ sender: Player, context ->
            val player = context[playerAArg].findFirstPlayer(sender)
            val pos = context[posArg].from(player?.position).asPos()
            player?.teleport(pos)
        }, playerAArg, posArg)
    }
}
