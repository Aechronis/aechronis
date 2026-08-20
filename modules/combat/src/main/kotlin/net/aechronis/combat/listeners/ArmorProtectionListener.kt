package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.ArmorPiece
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.entity.EntityDamageEvent

object ArmorProtectionListener {
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.damage.type == DamageType.OUT_OF_WORLD) return
        event.damage.amount *= ArmorPiece.getTotalProtection(player)
    }

    fun init() {
        Combat.highPriorityEventNode.addListener(EntityDamageEvent::class.java, ArmorProtectionListener::onEntityDamage)
    }
}
