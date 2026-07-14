package net.aechronis.vanilla.commands

import net.aechronis.vanilla.utils.Message
import net.aechronis.vanilla.utils.VanillaCommand
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Give : VanillaCommand("give", "vanilla.give") {
    init {
        val playerArg = ArgumentType.Entity("player").singleEntity(true).onlyPlayers(true)
        val itemArg = ArgumentType.ItemStack("item")
        val amountArg = ArgumentType.Integer("amount").min(1).max(64 * 36)

        setDefaultExecutor { player: Player, _ ->
            Message.print(player, "Usage: /give <player> <item> [amount]")
        }

        addSyntax({ sender: Player, context ->
            val target =
                context[playerArg].findFirstPlayer(sender) ?: run {
                    Message.error(sender, "Player not found.")
                    return@addSyntax
                }
            val item = context[itemArg]
            target.inventory.addItemStack(item)
            Message.print(sender, "Gave ${item.amount()} ${item.material().name()} to ${target.username}")
        }, playerArg, itemArg)

        addSyntax({ sender: Player, context ->
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
