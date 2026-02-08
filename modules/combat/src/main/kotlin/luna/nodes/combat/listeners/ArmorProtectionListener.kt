package luna.nodes.combat.listeners

import luna.nodes.combat.Combat
import luna.nodes.combat.objects.ArmorPiece
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDamageEvent

object ArmorProtectionListener {
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        event.damage.amount *= ArmorPiece.getTotalProtection(player)
    }

    fun init() {
        Combat.highPriorityEventNode.addListener(EntityDamageEvent::class.java, ArmorProtectionListener::onEntityDamage)
    }
}
