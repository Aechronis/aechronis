/**
 * Nodes chat listener
 */

package net.aechronis.nodes.listeners

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.chat.Chat
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
