package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Message
import net.minestom.server.command.builder.arguments.ArgumentType

class GiveCommand : Command("give", "vanilla.give") {
    init {
        val playerArg = ArgumentType.Entity("player").singleEntity(true).onlyPlayers(true)
        val itemArg = ArgumentType.ItemStack("item")
        val amountArg = ArgumentType.Integer("amount").min(1).max(64 * 36)

        setDefaultExecutor { player, _ ->
            Message.print(player, "Usage: /give <player> <item> [amount]")
        }

        addSyntax({ sender, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }
            val item = context[itemArg]
            target.inventory.addItemStack(item)
            Message.print(sender, "Gave ${item.amount()} ${item.material().name()} to ${target.username}")
        }, playerArg, itemArg)

        addSyntax({ sender, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }
            val item = context[itemArg].withAmount(context[amountArg])
            target.inventory.addItemStack(item)
        }, playerArg, itemArg, amountArg)
    }
}
