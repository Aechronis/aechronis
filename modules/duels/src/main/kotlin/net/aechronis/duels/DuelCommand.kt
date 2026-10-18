package net.aechronis.duels

import net.aechronis.utils.Command
import net.aechronis.vanilla.commands.PlayerTargets
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class DuelCommand : Command("duel") {
    private val playerArg = PlayerTargets.argument("player")
    private val optionalName = ArgumentType.Word("player")

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(
                Component.text("Usage: /duel <player> | /duel accept [player] | /duel deny [player]", NamedTextColor.LIGHT_PURPLE),
            )
        }
        addSyntax({ player: Player, _ -> Invites.accept(player, null) }, ArgumentType.Literal("accept"))
        addSyntax(
            { player: Player, context -> Invites.accept(player, context[optionalName]) },
            ArgumentType.Literal("accept"),
            optionalName,
        )
        addSyntax({ player: Player, _ -> Invites.deny(player, null) }, ArgumentType.Literal("deny"))
        addSyntax({ player: Player, context -> Invites.deny(player, context[optionalName]) }, ArgumentType.Literal("deny"), optionalName)
        addSyntax({ player: Player, context ->
            val target = PlayerTargets.resolve(player, context[playerArg])?.singleOrNull() ?: return@addSyntax
            when {
                target === player -> player.sendMessage(Component.text("You cannot duel yourself.", NamedTextColor.RED))
                MatchManager.inMatch(player) -> player.sendMessage(Component.text("You are already in a match.", NamedTextColor.RED))
                else -> DuelMenus.openKits(player, target)
            }
        }, playerArg)
    }
}
