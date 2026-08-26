package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDamageEvent

object RespawnProtectionListener {
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (Combat.isRespawnProtected(player)) {
            event.isCancelled = true
        }
    }

    fun onExternalPlayerDamage(event: EntityDamageEvent) {
        if (event.isCancelled || Combat.activeDamage(event.entity) === event.damage) return
        Combat.revokeRespawnProtectionAfterSuccessfulPlayerDamage(event.entity, event.damage)
    }

    fun init() {
        Combat.highPriorityEventNode.addListener(EntityDamageEvent::class.java, RespawnProtectionListener::onEntityDamage)
        Combat.lowPriorityEventNode.addListener(EntityDamageEvent::class.java, RespawnProtectionListener::onExternalPlayerDamage)
    }
}
