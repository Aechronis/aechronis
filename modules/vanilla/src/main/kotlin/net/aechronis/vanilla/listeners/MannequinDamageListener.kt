package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Mannequin
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.player.PlayerEntityInteractEvent

object MannequinDamageListener {
    fun onEntityDamage(event: EntityDamageEvent) {
        if (event.entity.entityType == EntityType.MANNEQUIN) event.isCancelled = true
    }

    fun onInteract(event: PlayerEntityInteractEvent) {
        if (event.hand != PlayerHand.MAIN) return
        val target = event.target as? EntityCreature ?: return
        if (target.entityType != EntityType.MANNEQUIN) return
        val inv = Mannequin.inventories[target] ?: return
        event.player.openInventory(inv)
    }

    fun init() {
        Vanilla.eventNode.addListener(EntityDamageEvent::class.java, MannequinDamageListener::onEntityDamage)
        Vanilla.eventNode.addListener(PlayerEntityInteractEvent::class.java, MannequinDamageListener::onInteract)
    }
}
