package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.minestom.server.entity.EntityType
import net.minestom.server.event.entity.EntityDamageEvent

object MannequinDamageListener {
    fun onEntityDamage(event: EntityDamageEvent) {
        if (event.entity.entityType == EntityType.MANNEQUIN) event.isCancelled = true
    }

    fun init() {
        Combat.highPriorityEventNode.addListener(EntityDamageEvent::class.java, MannequinDamageListener::onEntityDamage)
    }
}
