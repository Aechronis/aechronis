package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Give : Command("give", "vanilla.give") {
    init {
        val playerArg = ArgumentType.Entity("player").singleEntity(true).onlyPlayers(true)
        val itemArg = ArgumentType.ItemStack("item")
        val amountArg = ArgumentType.Integer("amount").min(1).max(64 * 36)

        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage: /give <player> <item> [amount]", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                    return@addSyntax
                }
            val item = context[itemArg]
            target.inventory.addItemStack(item)
            sender.sendMessage(
                Component.text(
                    "Gave ${item.amount()} ${item.material().name()} to ${target.username}",
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
        }, playerArg, itemArg)

        addSyntax({ sender: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                    return@addSyntax
                }
            val item = context[itemArg].withAmount(context[amountArg])
            target.inventory.addItemStack(item)
        }, playerArg, itemArg, amountArg)
    }
}
