/**
 * Contains all command executors to join/leave
 * ingame chat channels
 */

package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.chat.Chat
import net.aechronis.nodes.chat.ChatMode
import net.aechronis.nodes.objects.NodesCommand

class GlobalChatCommand : NodesCommand("globalchat", null, "gc") {
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

class GlobalChatJoinCommand : NodesCommand("join", null, "unmute") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /globalchat join")
        }

        addSyntax({ player, resident, context ->
            Chat.enableGlobalChat(player)
        })
    }
}

class GlobalChatLeaveCommand : NodesCommand("leave", null, "mute") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /globalchat leave")
        }

        addSyntax({ player, resident, context ->
            Chat.disableGlobalChat(player)
        })
    }
}

class TownChatCommand : NodesCommand("townchat", null, "tc") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /townchat")
        }

        addSyntax({ player, resident, town, context ->
            Chat.toggleChatMode(player, resident, ChatMode.TOWN)
        })
    }
}

class NationChatCommand : NodesCommand("nationchat", null, "nc") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /nationchat")
        }

        addSyntax({ player, resident, town, nation, context ->
            Chat.toggleChatMode(player, resident, ChatMode.NATION)
        })
    }
}

class AllyChatCommand : NodesCommand("allychat", null, "ac") {
    init {
        setDefaultExecutor { player, resident, context ->
            Message.print(player, "Usage: /allychat")
        }

        addSyntax({ player, resident, town, nation, context ->
            Chat.toggleChatMode(player, resident, ChatMode.ALLY)
        })
    }
}
