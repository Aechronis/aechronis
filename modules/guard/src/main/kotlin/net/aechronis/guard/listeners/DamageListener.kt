package net.aechronis.guard.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.guard.Guard
import net.aechronis.guard.flags.FlagName
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.entity.EntityDamageEvent

object DamageListener {
    fun handle(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val victim = event.entity as? Player ?: return
        val flag = flagFor(event)
        val position = victim.position
        Guard.check(victim, position.blockX(), position.blockY(), position.blockZ(), flag) {
            event.isCancelled = true
        }
        if (event.isCancelled) return

        // A player standing in a zone that denies this damage cannot deal it to
        // anyone outside either, e.g. shooting out of a safe zone.
        val attacker = event.damage.attacker as? Player ?: return
        if (attacker === victim) return
        val origin = attacker.position
        Guard.check(attacker, origin.blockX(), origin.blockY(), origin.blockZ(), flag) {
            event.isCancelled = true
        }
    }

    private fun flagFor(event: EntityDamageEvent): FlagName =
        when (Combat.damageKind(event.damage)) {
            CombatDamageKind.EXPLOSION -> FlagName.EXPLOSION
            CombatDamageKind.VEHICLE -> FlagName.OTHER_DAMAGE
            CombatDamageKind.PROJECTILE, CombatDamageKind.MELEE -> FlagName.DAMAGE
            null ->
                when (event.damage.type) {
                    DamageType.EXPLOSION, DamageType.PLAYER_EXPLOSION -> FlagName.EXPLOSION
                    else -> if (event.damage.attacker is Player) FlagName.DAMAGE else FlagName.OTHER_DAMAGE
                }
        }
}
