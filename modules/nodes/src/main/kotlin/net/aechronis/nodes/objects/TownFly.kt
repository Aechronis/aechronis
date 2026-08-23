package net.aechronis.nodes.objects

import net.minestom.server.entity.Player
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect

object TownFly {
    fun isAllowed(town: Town?, territory: Territory?): Boolean {
        val territoryTown = territory?.town ?: return false
        if (territoryTown === town) return true

        val nation = town?.nation
        return nation != null && territoryTown.nation === nation
    }

    fun disable(player: Player) {
        player.isAllowFlying = false
        player.addEffect(Potion(PotionEffect.SLOW_FALLING, 0, 100))
    }
}
