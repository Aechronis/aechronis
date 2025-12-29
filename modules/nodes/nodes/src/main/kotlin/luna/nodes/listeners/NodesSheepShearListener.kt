///**
// * Only allow shearing sheep in areas with sheep resource
// */
//
//package luna.nodes.listeners
//
//import org.bukkit.entity.Entity
//import org.bukkit.entity.EntityType
//import org.bukkit.event.EventHandler
//import org.bukkit.event.EventPriority
//import org.bukkit.event.Listener
//import org.bukkit.event.player.PlayerShearEntityEvent
//import luna.nodes.Config
//import luna.nodes.Message
//import luna.nodes.Nodes
//import luna.nodes.objects.Territory
//
//public class NodesSheepShearListener : Listener {
//
//    @EventHandler(priority = EventPriority.HIGH)
//    public fun onEntityShear(event: PlayerShearEntityEvent) {
//        // require shearing sheep in sheep node
//        if (Config.requireSheepNodeToShear) {
//            val entity: Entity = event.getEntity()
//            val territory: Territory? = Nodes.getTerritoryFromChunk(entity.location.chunk)
//
//            if (territory?.animals?.contains(EntityType.SHEEP) != true) {
//                val player = event.player
//                Message.error(player, "You can only collect wool in sheep nodes")
//                event.setCancelled(true)
//            }
//        }
//    }
//}
