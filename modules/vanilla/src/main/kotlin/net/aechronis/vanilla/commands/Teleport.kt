package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Teleport : Command("teleport", "vanilla.teleport", "tp") {
    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/teleport <playerA-name>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/teleport <position>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/teleport <playerA-name> <playerB-name>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/teleport <playerA-name> <position>", NamedTextColor.LIGHT_PURPLE))
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
