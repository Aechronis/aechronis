///**
// * Nodes chat listener
// */
//
//package luna.nodes.listeners
//
//import org.bukkit.event.EventHandler
//import org.bukkit.event.Listener
//import org.bukkit.event.player.AsyncPlayerChatEvent
//import luna.nodes.chat.Chat
//
//public class NodesChatListener : Listener {
//
//    @EventHandler
//    public fun onPlayerChat(event: AsyncPlayerChatEvent) {
//        if (event.isCancelled()) return
//
//        Chat.process(event)
//    }
//}
