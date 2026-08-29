package net.aechronis.vanilla.listeners

import net.aechronis.server.modules.ModuleEvents
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Combat
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.player.PlayerDisconnectEvent

object CombatListener {
    fun onDamage(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val victim = event.entity as? Player ?: return
        val attacker = event.damage.attacker as? Player ?: return
        if (attacker.uuid == victim.uuid) return

        Combat.tag(attacker, victim)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player
        val wasInCombat = Combat.isInCombat(player)

        Combat.clear(player)
        if (wasInCombat) {
            // Death listeners run synchronously; retain their penalties without persisting zero health.
            player.kill()
            player.heal()
        }
    }

    fun init() {
        val combatEventNode = EventNode.all("vanilla-combat").setPriority(1000)
        combatEventNode.addListener(EntityDamageEvent::class.java, CombatListener::onDamage)
        ModuleEvents.addChild(Vanilla.eventNode, combatEventNode)

        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, CombatListener::onDisconnect)
    }
}
