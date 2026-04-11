package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.Nodes.getRelationshipOfPlayerToPlayer
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDamageEvent

object NodesPlayerDamageListener {
    private fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity
        val attacker = event.damage.attacker

        if (victim !is Player || attacker !is Player) return

        // if relationship is ally, town or nation, and config specifies it, cancel event and notify attacker
        val relationship = getRelationshipOfPlayerToPlayer(victim, attacker)
        val (cancel, message) = when (relationship) {
            DiplomaticRelationship.TOWN, DiplomaticRelationship.NATION -> {
                val cancelled = !Nodes.config.allowNationFriendlyFire
                cancelled to if (cancelled) "You cannot attack members of your nation" else ""
            }

            DiplomaticRelationship.ALLY -> {
                val cancelled = !Nodes.config.allowAllyFriendlyFire
                cancelled to if (cancelled) "You cannot attack your allies" else ""
            }

            else -> false to ""
        }

        if (cancel) {
            event.isCancelled = true
            Message.error(attacker, message)
        }
    }

    fun init() {
        Nodes.eventNode.addListener(EntityDamageEvent::class.java, this::onDamage)
    }
}
