///**
// * Event that triggers when a town pair's truce expires
// */
//
//package luna.nodes.event
//
//import org.bukkit.event.Event
//import org.bukkit.event.HandlerList
//import luna.nodes.objects.Town
//
//public class TruceExpiredEvent(
//    public val town1: Town,
//    public val town2: Town,
//) : Event() {
//
//    override fun getHandlers(): HandlerList = HANDLERS
//
//    companion object {
//        private val HANDLERS: HandlerList = HandlerList()
//
//        @JvmStatic
//        fun getHandlerList(): HandlerList = HANDLERS
//    }
//}
