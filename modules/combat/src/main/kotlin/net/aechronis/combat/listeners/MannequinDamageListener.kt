package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Vehicle
import net.aechronis.utils.EntityTags
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.Damage
import net.minestom.server.event.entity.EntityDamageEvent

object MannequinDamageListener {
    private val forwarding = HashSet<Player>()

    fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity

        // other mannequins (such as corpses) ignore damage entirely
        if (
            entity.entityType == EntityType.MANNEQUIN &&
            entity.getTag(EntityTags.DAMAGEABLE_MANNEQUIN) != true
        ) {
            event.isCancelled = true
            return
        }

        // occupants of a protecting vehicle are invulnerable while riding
        if (entity is Player && entity !in forwarding && Vehicle.isProtectedOccupant(entity)) {
            event.isCancelled = true
        }
    }

    /** Applies a proxy body's damage to its owner while bypassing vehicle occupant protection. */
    fun forwardDamage(
        player: Player,
        damage: Damage,
    ) {
        forwarding.add(player)
        try {
            Combat.applyDamage(player, damage)
        } finally {
            forwarding.remove(player)
        }
    }

    fun init() {
        Combat.highPriorityEventNode.addListener(EntityDamageEvent::class.java, MannequinDamageListener::onEntityDamage)
    }

    fun shutdown() {
        forwarding.clear()
    }
}
