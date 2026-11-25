package net.aechronis.chat

import net.aechronis.chat.commands.ChatFilterCommand
import net.aechronis.chat.commands.Ignore
import net.aechronis.chat.commands.Message
import net.aechronis.chat.commands.Reply
import net.aechronis.server.hasPermission
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModulePermissions
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatModule : AechronisModule {
    companion object {
        const val REPEAT_COOLDOWN_MS = 3000L

        /** LuckPerms node that exempts a player from the repeat-message cooldown. */
        const val REPEAT_BYPASS_PERMISSION = "chat.bypass.repeat"
    }

    override val id = "chat"
    override val dependencies = setOf("utils", "vanilla")

    override fun initialize(context: ModuleContext) {
        val store = IgnoreStore(Path.of("chat", "ignored"))
        store.initialize()
        Chat.init(store)
        ChatFilter.init(Path.of("chat", "filter.txt"))
        ModuleCommands.register(Message(), Reply(), Ignore(), ChatFilterCommand())
        ModulePermissions.register(REPEAT_BYPASS_PERMISSION)
        context.addListener(PlayerChatEvent::class.java) { event ->
            if (!event.isCancelled && ChatFilter.blocks(event.player, event.rawMessage)) event.isCancelled = true
            if (!event.isCancelled && repeatBlocked(event.player, event.rawMessage)) event.isCancelled = true
        }
        context.addListener(PlayerDisconnectEvent::class.java) { event ->
            lastMessages.remove(event.player.uuid)
            Chat.removeLastSenderReferences(event.player)
        }
    }

    private class LastMessage(
        val text: String,
        val sentAt: Long,
    )

    private val lastMessages = ConcurrentHashMap<UUID, LastMessage>()

    /** True when [player] repeats their previous message within [REPEAT_COOLDOWN_MS]; the cooldown restarts only on sent messages. */
    private fun repeatBlocked(
        player: Player,
        message: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        val last = lastMessages[player.uuid]
        if (last != null &&
            last.text == message &&
            now - last.sentAt < REPEAT_COOLDOWN_MS &&
            !player.hasPermission(REPEAT_BYPASS_PERMISSION)
        ) {
            val secondsLeft = (REPEAT_COOLDOWN_MS - (now - last.sentAt) + 999) / 1000
            player.sendMessage(Component.text("Wait ${secondsLeft}s before sending that same message again.", NamedTextColor.RED))
            return true
        }
        lastMessages[player.uuid] = LastMessage(message, now)
        return false
    }

    override fun shutdown(context: ModuleContext) = Chat.shutdown()
}
