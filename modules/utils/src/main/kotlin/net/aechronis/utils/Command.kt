package net.aechronis.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.entity.Player
import net.minestom.server.command.builder.Command as MinestomCommand

/**
 * A player-only Minestom command with centralized permission handling.
 *
 * [permission] applies to every executor unless an executor-specific permission
 * is supplied. Libraries can override [canExecute] for additional policy while
 * retaining the common sender and permission checks.
 */
open class Command(
    name: String,
    val permission: String? = null,
    vararg aliases: String,
) : MinestomCommand(name, *aliases) {
    /**
     * Adds a default executor that requires a permitted player sender.
     */
    fun setDefaultExecutor(executor: (player: Player, context: CommandContext) -> Unit) =
        setDefaultExecutorWithPermission(permission, executor)

    /**
     * Adds a default executor with an executor-specific [permission].
     */
    fun setDefaultExecutor(
        permission: String,
        executor: (player: Player, context: CommandContext) -> Unit,
    ) = setDefaultExecutorWithPermission(permission, executor)

    private fun setDefaultExecutorWithPermission(
        permission: String?,
        executor: (player: Player, context: CommandContext) -> Unit,
    ) = super.setDefaultExecutor { sender, context ->
        validatedPlayer(sender, permission)?.let { executor(it, context) }
    }

    /**
     * Adds a syntax that requires a permitted player sender.
     */
    fun addSyntax(
        executor: (player: Player, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) = addSyntaxWithPermission(permission, executor, *args)

    /**
     * Adds a syntax with an executor-specific [permission].
     */
    fun addSyntax(
        permission: String,
        executor: (player: Player, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) = addSyntaxWithPermission(permission, executor, *args)

    private fun addSyntaxWithPermission(
        permission: String?,
        executor: (player: Player, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) = super.addSyntax({ sender, context ->
        validatedPlayer(sender, permission)?.let { executor(it, context) }
    }, *args)

    /** Allows libraries to impose additional execution policy. */
    protected open fun canExecute(player: Player): Boolean = true

    /** Allows libraries to extend the common permission policy. */
    protected open fun hasPermission(
        player: Player,
        permission: String?,
    ): Boolean = player.hasPermission(permission)

    private fun validatedPlayer(
        sender: CommandSender,
        permission: String?,
    ): Player? {
        if (sender !is Player) {
            sender.sendMessage(Component.text(PLAYER_ONLY_MESSAGE, NamedTextColor.RED))
            return null
        }

        if (!hasPermission(sender, permission)) {
            sender.sendMessage(Component.text(PERMISSION_DENIED_MESSAGE, NamedTextColor.RED))
            return null
        }

        return sender.takeIf(::canExecute)
    }

    private companion object {
        const val PLAYER_ONLY_MESSAGE = "This command can only be used by players"
        const val PERMISSION_DENIED_MESSAGE = "You don't have permission to use this command"
    }
}
