/**
 * Contains all command executors to join/leave
 * ingame chat channels
 */

package luna.nodes.commands

import luna.nodes.Message
import luna.nodes.chat.Chat
import luna.nodes.chat.ChatMode
import luna.nodes.objects.Command

class GlobalChatCommand : Command("globalchat", "gc") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /globalchat [join/leave]")
        }

        addSyntax( { player, resident, context ->
            Chat.toggleChatMode(player, resident, ChatMode.GLOBAL)
        })

        addSubcommand(GlobalChatJoinCommand())
        addSubcommand(GlobalChatLeaveCommand())
    }
}

class GlobalChatJoinCommand : Command("join", "unmute") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /globalchat [join/leave]")
        }

        addSyntax( { player, resident, context ->
            Chat.enableGlobalChat(player)
        })
    }
}

class GlobalChatLeaveCommand : Command("leave", "mute") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /globalchat [join/leave]")
        }

        addSyntax( { player, resident, context ->
            Chat.disableGlobalChat(player)
        })
    }
}

class TownChatCommand : Command("townchat", "tc") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /townchat")
        }

        addSyntax( { player, resident, town, context ->
            Chat.toggleChatMode(player, resident, ChatMode.TOWN)
        })
    }
}

class NationChatCommand : Command("nationchat", "nc") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /nationchat")
        }

        addSyntax( { player, resident, town, nation, context ->
            Chat.toggleChatMode(player, resident, ChatMode.NATION)
        })
    }
}

class AllyChatCommand : Command("allychat", "ac") {
    init {
        setDefaultExecutor { sender, context ->
            Message.print(sender, "Usage: /allychat")
        }

        addSyntax( { player, resident, town, nation, context ->
            Chat.toggleChatMode(player, resident, ChatMode.ALLY)
        })
    }
}
