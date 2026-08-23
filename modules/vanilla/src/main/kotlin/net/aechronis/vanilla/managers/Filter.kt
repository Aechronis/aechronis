package net.aechronis.vanilla.managers

import com.modernmt.text.profanity.ProfanityFilter
import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Filter {
    private const val LANGUAGE = "en"
    private const val MESSAGE_INTERVAL_MS = 1_000L
    private val profanityFilter = ProfanityFilter()
    private val lastMessageTimes = ConcurrentHashMap<UUID, Long>()

    fun onChat(event: PlayerChatEvent) {
        val now = System.currentTimeMillis()
        val previous = lastMessageTimes.put(event.player.uuid, now)
        if (previous != null && now - previous < MESSAGE_INTERVAL_MS) {
            event.isCancelled = true
            event.player.sendMessage(Component.text("You are sending messages too quickly.", NamedTextColor.RED))
            return
        }

        if (!profanityFilter.test(LANGUAGE, event.rawMessage)) return

        event.isCancelled = true
        event.player.sendMessage(Component.text("Your message was blocked.", NamedTextColor.RED))
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        lastMessageTimes.remove(event.player.uuid)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerChatEvent::class.java, ::onChat)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
    }
}
