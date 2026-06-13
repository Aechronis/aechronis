package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Shop
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDeathEvent

object ShopKillListener {
    fun onDeath(event: PlayerDeathEvent) {
        val killer = event.player.lastDamageSource?.attacker as? Player ?: return
        killer.setTag(Shop.POINTS_TAG, (killer.getTag(Shop.POINTS_TAG) ?: 0) + 1)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerDeathEvent::class.java, ShopKillListener::onDeath)
    }
}
