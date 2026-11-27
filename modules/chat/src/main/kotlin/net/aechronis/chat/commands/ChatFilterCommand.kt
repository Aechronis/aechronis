package net.aechronis.chat.commands

import net.aechronis.chat.ChatFilter
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType

class ChatFilterCommand : Command("chatfilter", PERMISSION) {
    companion object {
        /** LuckPerms node for viewing and editing the blocked word list. */
        const val PERMISSION = "chat.filter"
    }

    private val wordArg = ArgumentType.Word("word")

    init {
        setSenderDefaultExecutor { sender, _ ->
            sender.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            sender.sendMessage(Component.text("/chatfilter add <word>", NamedTextColor.LIGHT_PURPLE))
            sender.sendMessage(Component.text("/chatfilter remove <word>", NamedTextColor.LIGHT_PURPLE))
            sender.sendMessage(Component.text("/chatfilter list", NamedTextColor.LIGHT_PURPLE))
        }

        addSenderSyntax({ sender, _ ->
            sender.sendMessage(Component.text("Blocked words: ${ChatFilter.list().joinToString(", ")}", NamedTextColor.LIGHT_PURPLE))
        }, ArgumentType.Literal("list"))

        addSenderSyntax({ sender, context ->
            val word = ChatFilter.normalizeWord(context[wordArg])
            when {
                word.isEmpty() -> sender.sendMessage(Component.text("Words must contain letters.", NamedTextColor.RED))
                ChatFilter.add(word) -> sender.sendMessage(Component.text("Added '$word' to the chat filter.", NamedTextColor.GREEN))
                else -> sender.sendMessage(Component.text("'$word' is already filtered.", NamedTextColor.RED))
            }
        }, ArgumentType.Literal("add"), wordArg)

        addSenderSyntax({ sender, context ->
            val word = ChatFilter.normalizeWord(context[wordArg])
            if (ChatFilter.remove(word)) {
                sender.sendMessage(Component.text("Removed '$word' from the chat filter.", NamedTextColor.GREEN))
            } else {
                sender.sendMessage(Component.text("'$word' is not filtered.", NamedTextColor.RED))
            }
        }, ArgumentType.Literal("remove"), wordArg)
    }
}
