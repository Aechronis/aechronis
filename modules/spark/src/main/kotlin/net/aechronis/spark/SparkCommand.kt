package net.aechronis.spark

import me.lucko.spark.common.SparkPlatform
import me.lucko.spark.common.command.sender.AbstractCommandSender
import net.aechronis.server.hasPermission
import net.kyori.adventure.text.Component
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player
import java.util.UUID

internal class SparkCommand(
    platform: SparkPlatform,
) : Command("spark") {
    init {
        setCondition { sender, _ -> platform.hasPermissionForAnyCommand(SparkCommandSender(sender)) }
        setDefaultExecutor { sender, _ -> platform.executeCommand(SparkCommandSender(sender), emptyArray()) }
        val args = ArgumentType.StringArray("args")
        args.setSuggestionCallback { sender, context, suggestion ->
            // Keep the final empty argument so Spark can complete the next word after a space.
            val input =
                context.input
                    .substringAfter(' ', "")
                    .split(' ')
                    .toTypedArray()
            platform.tabCompleteCommand(SparkCommandSender(sender), input).forEach {
                suggestion.addEntry(SuggestionEntry(it))
            }
        }
        addSyntax({ sender, context -> platform.executeCommand(SparkCommandSender(sender), context[args]) }, args)
    }
}

internal class SparkCommandSender(
    sender: CommandSender,
) : AbstractCommandSender<CommandSender>(sender) {
    override fun getName(): String = (delegate as? Player)?.username ?: "Console"

    override fun getUniqueId(): UUID? = (delegate as? Player)?.uuid

    override fun sendMessage(message: Component) = delegate.sendMessage(message)

    override fun hasPermission(permission: String): Boolean = (delegate as? Player)?.hasPermission(permission) ?: true
}
