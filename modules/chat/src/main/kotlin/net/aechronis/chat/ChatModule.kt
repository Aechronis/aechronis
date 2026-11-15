package net.aechronis.chat

import net.aechronis.chat.commands.ChatFilterCommand
import net.aechronis.chat.commands.Ignore
import net.aechronis.chat.commands.Message
import net.aechronis.chat.commands.Reply
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import java.nio.file.Path

class ChatModule : AechronisModule {
    override val id = "chat"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        val store = IgnoreStore(Path.of("chat", "ignored"))
        store.initialize()
        Chat.init(store)
        ChatFilter.init(Path.of("chat", "filter.txt"))
        ModuleCommands.register(Message(), Reply(), Ignore(), ChatFilterCommand())
        context.addListener(PlayerChatEvent::class.java) { event ->
            if (!event.isCancelled && ChatFilter.blocks(event.player, event.rawMessage)) event.isCancelled = true
        }
        context.addListener(PlayerDisconnectEvent::class.java) { event ->
            Chat.removeLastSenderReferences(event.player)
        }
    }

    override fun shutdown(context: ModuleContext) = Chat.shutdown()
}
