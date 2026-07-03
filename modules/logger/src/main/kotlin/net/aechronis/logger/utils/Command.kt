package net.aechronis.logger.utils

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
    /**
     * When true, a permission-backend lookup failure denies rather than allows. Opt in for
     * destructive commands (rollback/undo/redo) -- the default stays fail-open to match every
     * existing command's behavior. Must be passed as a named argument (it follows a vararg).
     */
    private val failClosed: Boolean = false,
) : Command(name, *aliases) {
    private fun checkPermission(sender: Player): Boolean {
        if (permission == null) return true
        return if (failClosed) hasPermission(sender, permission) else hasPermission(sender, permission)
    }

    /**
     * Add a default executor that requires the sender to be a player.
     */
    fun setDefaultExecutor(executor: (player: Player, context: CommandContext) -> Unit) {
        super.setDefaultExecutor { sender: CommandSender, context ->
            if (sender !is Player) {
                sender.sendMessage(Component.text("This command can only be used by players", NamedTextColor.RED))
                return@setDefaultExecutor
            }

            if (!checkPermission(sender)) {
                sender.sendMessage(Component.text("You don't have permission to use this command", NamedTextColor.RED))
                return@setDefaultExecutor
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

            if (!checkPermission(sender)) {
                sender.sendMessage(Component.text("You don't have permission to use this command", NamedTextColor.RED))
                return@addSyntax
            }

            executor(sender, context)
        }, *args)
    }
}
