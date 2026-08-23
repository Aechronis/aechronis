package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Give : Command("give", "vanilla.give") {
    private val playerArg = PlayerTargets.argument()
    private val itemArg = ArgumentType.ItemStack("item")
    private val amountArg = ArgumentType.Integer("amount").min(1).max(64 * 36)

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage: /give <player> <item> [amount]", NamedTextColor.LIGHT_PURPLE))
        }

        addSenderSyntax({ sender, context ->
            executeGive(sender, context)
        }, playerArg, itemArg)

        addSenderSyntax({ sender, context ->
            executeGive(sender, context)
        }, playerArg, itemArg, amountArg)
    }

    private fun executeGive(
        sender: CommandSender,
        context: CommandContext,
    ) {
        val targets =
            PlayerTargets.resolve(context[playerArg], MinecraftServer.getConnectionManager().onlinePlayers)
                ?: run {
                    if (sender is Player) sender.sendMessage(Component.text("Player not found: ${context[playerArg]}", NamedTextColor.RED))
                    return
                }
        val item = context[itemArg].let { stack -> if (context.has(amountArg)) stack.withAmount(context[amountArg]) else stack }

        targets.forEach { target ->
            if (!target.inventory.addItemStack(item)) target.dropItem(item)
        }
        if (sender is Player) {
            sender.sendMessage(
                Component.text(
                    "Gave ${item.amount()} ${item.material().name()} to ${targets.size} player(s)",
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
        }
    }
}
