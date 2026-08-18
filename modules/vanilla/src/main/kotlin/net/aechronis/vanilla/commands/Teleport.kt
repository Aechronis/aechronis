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

        val playerAArg = PlayerTargets.argument("playerA-name")
        val playerBArg = PlayerTargets.argument("playerB-name")
        val posArg = ArgumentType.RelativeVec3("position")

        // teleport self to other player
        addSyntax({ sender: Player, context ->
            val targets = PlayerTargets.resolve(sender, context[playerAArg]) ?: return@addSyntax
            if (targets.size != 1) {
                sender.sendMessage(Component.text("Use /teleport * <player|position> to teleport all players.", NamedTextColor.RED))
                return@addSyntax
            }
            sender.teleport(targets.single().position)
        }, playerAArg)

        // teleport self to coords
        addSyntax({ sender: Player, context ->
            val pos = context[posArg].from(sender.position).asPos()
            sender.teleport(pos)
        }, posArg)

        // teleport player to other player
        addSyntax({ sender: Player, context ->
            val targets = PlayerTargets.resolve(sender, context[playerAArg]) ?: return@addSyntax
            val destinations = PlayerTargets.resolve(sender, context[playerBArg]) ?: return@addSyntax
            if (destinations.size != 1) {
                sender.sendMessage(Component.text("The destination must be one player.", NamedTextColor.RED))
                return@addSyntax
            }
            val destination = destinations.single().position
            targets.forEach { it.teleport(destination) }
        }, playerAArg, playerBArg)

        // teleport player to coords
        addSyntax({ sender: Player, context ->
            val targets = PlayerTargets.resolve(sender, context[playerAArg]) ?: return@addSyntax
            targets.forEach { target ->
                val pos = context[posArg].from(target.position).asPos()
                target.teleport(pos)
            }
        }, playerAArg, posArg)
    }
}
