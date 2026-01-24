/**
 * Nodes chat listener
 */

package luna.nodes.listeners

import luna.nodes.Nodes
import luna.nodes.chat.Chat
import net.minestom.server.event.player.PlayerChatEvent

object NodesChatListener {
    private fun onPlayerChat(event: PlayerChatEvent) {
        if (event.isCancelled) return

        Chat.process(event)
    }

    fun init() {
        Nodes.eventNode.addListener(PlayerChatEvent::class.java, NodesChatListener::onPlayerChat)
    }
}
