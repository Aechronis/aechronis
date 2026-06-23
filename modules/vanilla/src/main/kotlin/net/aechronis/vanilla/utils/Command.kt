package net.aechronis.vanilla.utils

import net.aechronis.vanilla.utils.PlayerAddons.hasPermission
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
                Message.error(sender, "This command can only be used by players")
                return@setDefaultExecutor
            }

            if (permission != null) {
                if (!sender.hasPermission(permission)) {
                    Message.error(sender, "You don't have permission to use this command")
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
                Message.error(sender, "This command can only be used by players")
                return@addSyntax
            }

            if (permission != null) {
                if (!sender.hasPermission(permission)) {
                    Message.error(sender, "You don't have permission to use this command")
                    return@addSyntax
                }
            }

            executor(sender, context)
        }, *args)
    }
}
