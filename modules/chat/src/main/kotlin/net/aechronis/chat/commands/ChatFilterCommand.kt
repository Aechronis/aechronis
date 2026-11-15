package net.aechronis.chat.commands

import net.aechronis.chat.ChatFilter
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class ChatFilterCommand : Command("chatfilter", PERMISSION) {
    companion object {
        /** LuckPerms node for viewing and editing the blocked word list. */
        const val PERMISSION = "chat.filter"
    }

    private val wordArg = ArgumentType.Word("word")

    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/chatfilter add <word>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/chatfilter remove <word>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/chatfilter list", NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ player: Player, _ ->
            player.sendMessage(Component.text("Blocked words: ${ChatFilter.list().joinToString(", ")}", NamedTextColor.LIGHT_PURPLE))
        }, ArgumentType.Literal("list"))

        addSyntax({ player: Player, context ->
            val word = ChatFilter.normalizeWord(context[wordArg])
            when {
                word.isEmpty() -> player.sendMessage(Component.text("Words must contain letters.", NamedTextColor.RED))
                ChatFilter.add(word) -> player.sendMessage(Component.text("Added '$word' to the chat filter.", NamedTextColor.GREEN))
                else -> player.sendMessage(Component.text("'$word' is already filtered.", NamedTextColor.RED))
            }
        }, ArgumentType.Literal("add"), wordArg)

        addSyntax({ player: Player, context ->
            val word = ChatFilter.normalizeWord(context[wordArg])
            if (ChatFilter.remove(word)) {
                player.sendMessage(Component.text("Removed '$word' from the chat filter.", NamedTextColor.GREEN))
            } else {
                player.sendMessage(Component.text("'$word' is not filtered.", NamedTextColor.RED))
            }
        }, ArgumentType.Literal("remove"), wordArg)
    }
}
