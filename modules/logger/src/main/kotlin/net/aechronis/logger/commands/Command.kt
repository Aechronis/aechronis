package net.aechronis.logger.commands

import net.aechronis.logger.utils.hasPermission
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.entity.Player

open class Command(
    name: String,
    val permission: String? = null,
    vararg aliases: String,
) : Command(name, *aliases) {
    /**
     * Add a default executor that requires the sender to be a player.
     */
    fun setDefaultExecutor(executor: (player: Player, context: CommandContext) -> Unit) {
        super.setDefaultExecutor { sender: CommandSender, context ->
            if (sender !is Player) {
                sender.sendMessage(Component.text("This command can only be used by players", NamedTextColor.RED))
                return@setDefaultExecutor
            }

            if (permission != null) {
                if (!hasPermission(sender, permission)) {
                    sender.sendMessage(Component.text("You don't have permission to use this command", NamedTextColor.RED))
                    return@setDefaultExecutor
                }
            }

            executor(sender, context)
        }
    }

    /**
     * Add a syntax that requires the sender to be a player.
     */
    fun addSyntax(
        executor: (player: Player, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        super.addSyntax({ sender, context ->
            if (sender !is Player) {
                sender.sendMessage(Component.text("This command can only be used by players", NamedTextColor.RED))
                return@addSyntax
            }

            if (permission != null) {
                if (!hasPermission(sender, permission)) {
                    sender.sendMessage(Component.text("You don't have permission to use this command", NamedTextColor.RED))
                    return@addSyntax
                }
            }

            executor(sender, context)
        }, *args)
    }
}