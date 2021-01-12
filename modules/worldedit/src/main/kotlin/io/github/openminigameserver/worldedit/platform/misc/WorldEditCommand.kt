package io.github.openminigameserver.worldedit.platform.misc

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.platform.CommandEvent
import com.sk89q.worldedit.event.platform.CommandSuggestionEvent
import com.sk89q.worldedit.internal.command.CommandUtil
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Arguments
import net.minestom.server.command.builder.arguments.ArgumentDynamicStringArray
import org.enginehub.piston.Command
import net.minestom.server.command.builder.Command as MinestomCommand


class WorldEditCommand(command: Command) : MinestomCommand(command.name, *command.aliases.toTypedArray()) {

    init {
        addSyntax({ _, _ -> }, ArgumentDynamicStringArray("args"))
    }

    override fun globalListener(sender: CommandSender, arguments: Arguments, command: String) {
        CommandEvent(
            MinestomAdapter.asActor(sender),
            "/$command"
        ).also {
            WorldEdit.getInstance().eventBus.post(it)
        }
    }

    override fun onDynamicWrite(sender: CommandSender, text: String): Array<String>? {
        val weEvent =
            CommandSuggestionEvent(MinestomAdapter.asActor(sender), text)
        WorldEdit.getInstance().eventBus.post(weEvent)
        return CommandUtil.fixSuggestions(text, weEvent.suggestions).toTypedArray()

    }
}