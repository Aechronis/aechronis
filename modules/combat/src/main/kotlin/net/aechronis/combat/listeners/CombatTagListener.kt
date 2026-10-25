package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.managers.CombatTags
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.player.PlayerDisconnectEvent

object CombatTagListener {
    fun onDamage(event: EntityDamageEvent) {
        if (event.isCancelled) return
        val victim = event.entity as? Player ?: return
        val attacker = event.damage.attacker as? Player ?: return
        if (attacker.uuid == victim.uuid) return

        CombatTags.tag(attacker, victim)
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player
        val wasInCombat = CombatTags.isInCombat(player)

        CombatTags.clear(player)
        if (wasInCombat) {
            // Disconnects cancel armed grenades and reloads before death callbacks run.
            Combat.playerStates[player]?.cancelActions()
            // Death listeners run synchronously; retain their penalties without persisting zero health.
            player.kill()
            player.heal()
        }
    }

    fun init() {
        // Priority orders siblings, so observe damage after protection in other modules.
        val combatEventNode = EventNode.all("combat-tags").setPriority(1000)
        combatEventNode.addListener(EntityDamageEvent::class.java, CombatTagListener::onDamage)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), combatEventNode)

        // Registered before Combat's disconnect cleanup, and before Vanilla saves player data.
        Combat.eventNode.addListener(PlayerDisconnectEvent::class.java, CombatTagListener::onDisconnect)
    }
}
