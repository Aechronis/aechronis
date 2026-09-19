package io.github.openminigameserver.worldedit.platform.misc

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.command.util.PermissionCondition
import com.sk89q.worldedit.event.platform.CommandEvent
import com.sk89q.worldedit.event.platform.CommandSuggestionEvent
import com.sk89q.worldedit.internal.command.CommandUtil
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import net.aechronis.server.modules.ModulePermissions
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player
import org.enginehub.piston.Command
import org.enginehub.piston.part.SubCommandPart
import net.minestom.server.command.builder.Command as MinestomCommand

class WorldEditCommand(
    command: Command,
) : MinestomCommand(command.name, *command.aliases.toTypedArray()) {
    init {
        registerPermissions(command)
        val argsArgument = ArgumentType.StringArray("args")
        argsArgument.setSuggestionCallback { sender, _, suggestion ->
            val weEvent = CommandSuggestionEvent(MinestomAdapter.asActor(sender), suggestion.input)
            WorldEdit.getInstance().eventBus.post(weEvent)
            CommandUtil.fixSuggestions(suggestion.input, weEvent.suggestions).forEach {
                suggestion.addEntry(SuggestionEntry(it))
            }
        }
        addSyntax({ _, _ -> }, argsArgument)
    }

    override fun globalListener(
        sender: CommandSender,
        context: CommandContext,
        command: String,
    ) {
        if (sender is Player && sender.instance == null) {
            sender.sendMessage(Component.text("You cannot use WorldEdit commands right now.", NamedTextColor.RED))
            return
        }

        val accepted =
            WorldEditExecutor.submit {
                try {
                    CommandEvent(
                        MinestomAdapter.asActor(sender),
                        "/$command",
                    ).also {
                        WorldEdit.getInstance().eventBus.post(it)
                    }
                } catch (e: Exception) {
                    println("Unexpected error while handling a WorldEdit command $e")
                }
            }
        if (!accepted) {
            sender.sendMessage(Component.text("WorldEdit is shutting down; try again after the module reload.", NamedTextColor.RED))
        }
    }
}

/** Read the complete command tree, including grouped commands such as schematic and brush. */
private fun registerPermissions(command: Command) {
    command.condition.`as`(PermissionCondition::class.java).ifPresent { condition ->
        ModulePermissions.register(*condition.permissions.toTypedArray())
    }
    command.parts.filterIsInstance<SubCommandPart>().forEach { part ->
        part.commands.forEach(::registerPermissions)
    }
}
