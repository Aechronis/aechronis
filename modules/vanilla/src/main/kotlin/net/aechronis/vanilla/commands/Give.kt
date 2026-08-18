package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Give : Command("give", "vanilla.give") {
    init {
        val playerArg = PlayerTargets.argument()
        val itemArg = ArgumentType.ItemStack("item")
        val amountArg = ArgumentType.Integer("amount").min(1).max(64 * 36)

        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage: /give <player> <item> [amount]", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, context ->
            val targets = PlayerTargets.resolve(sender, context[playerArg]) ?: return@addSyntax
            val item = context[itemArg]
            targets.forEach { target ->
                if (!target.inventory.addItemStack(item)) target.dropItem(item)
            }
            sender.sendMessage(
                Component.text(
                    "Gave ${item.amount()} ${item.material().name()} to ${targets.size} player(s)",
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
        }, playerArg, itemArg)

        addSyntax({ sender: Player, context ->
            val targets = PlayerTargets.resolve(sender, context[playerArg]) ?: return@addSyntax
            val item = context[itemArg].withAmount(context[amountArg])
            targets.forEach { target ->
                if (!target.inventory.addItemStack(item)) target.dropItem(item)
            }
        }, playerArg, itemArg, amountArg)
    }
}
