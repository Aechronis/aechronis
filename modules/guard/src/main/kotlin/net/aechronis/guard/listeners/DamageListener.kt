package net.aechronis.guard.listeners

import net.aechronis.guard.Guard
import net.aechronis.guard.flags.FlagName
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDamageEvent

object DamageListener {
    fun handle(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val victim = event.entity as? Player ?: return
        if (event.damage.attacker !is Player) return
        val position = victim.position
        Guard.check(victim, victim.instance, position.blockX(), position.blockY(), position.blockZ(), FlagName.DAMAGE) {
            event.isCancelled = true
        }
    }
}
