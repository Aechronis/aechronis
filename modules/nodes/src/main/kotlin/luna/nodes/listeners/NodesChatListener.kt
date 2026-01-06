/**
 * Nodes chat listener
 */

package luna.nodes.listeners

import luna.nodes.chat.Chat
import net.minestom.server.event.player.PlayerChatEvent

public fun onPlayerChat(event: PlayerChatEvent) {
    if (event.isCancelled) return

    Chat.process(event)
}
