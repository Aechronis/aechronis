/**
 * Nodes chat listener
 */

package luna.nodes.listeners

import luna.nodes.chat.Chat
import net.minestom.server.event.GlobalEventHandler
import net.minestom.server.event.player.PlayerChatEvent

object NodesChatListener {
    private fun onPlayerChat(event: PlayerChatEvent) {
        if (event.isCancelled) return

        Chat.process(event)
    }

    fun init(eventHandler: GlobalEventHandler) {
        eventHandler.addListener(PlayerChatEvent::class.java, NodesChatListener::onPlayerChat)
    }
}
