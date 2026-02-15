/**
 * Contains all command executors to join/leave
 * ingame chat channels
 */

package luna.nodes.nodes.commands

import luna.nodes.nodes.Message
import luna.nodes.nodes.chat.Chat
import luna.nodes.nodes.chat.ChatMode
import luna.nodes.nodes.objects.Command

class GlobalChatCommand : Command("globalchat", null, "gc") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage:")
            Message.print(player, "/globalchat")
            Message.print(player, "/globalchat join")
            Message.print(player, "/globalchat leave")
        }

        addSyntax({ player, resident, context ->
            Chat.toggleChatMode(player, resident, ChatMode.GLOBAL)
        })

        addSubcommand(GlobalChatJoinCommand())
        addSubcommand(GlobalChatLeaveCommand())
    }
}

class GlobalChatJoinCommand : Command("join", null, "unmute") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /globalchat join")
        }

        addSyntax({ player, resident, context ->
            Chat.enableGlobalChat(player)
        })
    }
}

class GlobalChatLeaveCommand : Command("leave", null, "mute") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /globalchat leave")
        }

        addSyntax({ player, resident, context ->
            Chat.disableGlobalChat(player)
        })
    }
}

class TownChatCommand : Command("townchat", null, "tc") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /townchat")
        }

        addSyntax({ player, resident, town, context ->
            Chat.toggleChatMode(player, resident, ChatMode.TOWN)
        })
    }
}

class NationChatCommand : Command("nationchat", null, "nc") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nationchat")
        }

        addSyntax({ player, resident, town, nation, context ->
            Chat.toggleChatMode(player, resident, ChatMode.NATION)
        })
    }
}

class AllyChatCommand : Command("allychat", null, "ac") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /allychat")
        }

        addSyntax({ player, resident, town, nation, context ->
            Chat.toggleChatMode(player, resident, ChatMode.ALLY)
        })
    }
}
