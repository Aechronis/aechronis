///**
// * Event that triggers when an alliance between
// * two towns is created
// */
//
//package luna.nodes.event
//
//import org.bukkit.event.Event
//import org.bukkit.event.HandlerList
//import luna.nodes.objects.Town
//
//public class AllianceCreatedEvent(
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
