package net.aechronis.luckperms

import me.lucko.luckperms.common.command.access.CommandPermission
import me.lucko.luckperms.common.command.utils.ArgumentTokenizer
import me.lucko.luckperms.common.sender.Sender
import me.lucko.luckperms.minestom.LPMinestomPlugin
import net.aechronis.server.Permissions
import net.luckperms.api.util.Tristate
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.CommandExecutor
import net.minestom.server.command.builder.suggestion.SuggestionEntry

/** Keep upstream syntax and normal checks, overriding only the command sender's permissions. */
internal fun enablePermissionBypass(
    command: Command,
    plugin: LPMinestomPlugin,
) {
    fun wrap(executor: CommandExecutor): CommandExecutor =
        CommandExecutor { sender, context ->
            if (Permissions.allPermissionsEnabled) {
                plugin.commandManager.executeCommand(
                    UnrestrictedSender(plugin.senderFactory.wrap(sender)),
                    context.commandName,
                    ArgumentTokenizer.EXECUTE.tokenizeInput(context.input.substringAfter(' ', "")),
                )
            } else {
                executor.apply(sender, context)
            }
        }

    command.defaultExecutor?.let { command.defaultExecutor = wrap(it) }
    command.syntaxes.forEach { it.executor = wrap(it.executor) }
    command.syntaxes
        .flatMap { it.arguments.toList() }
        .distinct()
        .forEach { argument ->
            val callback = argument.suggestionCallback ?: return@forEach
            argument.setSuggestionCallback { sender, context, suggestion ->
                if (Permissions.allPermissionsEnabled) {
                    plugin.commandManager
                        .tabCompleteCommand(
                            UnrestrictedSender(plugin.senderFactory.wrap(sender)),
                            ArgumentTokenizer.TAB_COMPLETE.tokenizeInput(context.input.substringAfter(' ', "")),
                        ).forEach { suggestion.addEntry(SuggestionEntry(it)) }
                } else {
                    callback.apply(sender, context, suggestion)
                }
            }
        }
}

/** Preserve player identity, replies and console-only restrictions without modifying stored nodes. */
private class UnrestrictedSender(
    private val delegate: Sender,
) : Sender by delegate {
    override fun getPermissionValue(permission: String): Tristate = Tristate.TRUE

    override fun hasPermission(permission: String): Boolean = true

    override fun hasPermission(permission: CommandPermission): Boolean = true
}
