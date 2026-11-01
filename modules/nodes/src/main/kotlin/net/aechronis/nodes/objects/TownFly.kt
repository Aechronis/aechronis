package net.aechronis.nodes.objects

import net.aechronis.nodes.war.FlagWar
import net.minestom.server.entity.GameMode
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
        player.isFlying = false
        player.isAllowFlying = false
        player.addEffect(Potion(PotionEffect.SLOW_FALLING, 0, 100))
    }

    fun disableDuringWar(player: Player): Boolean {
        if (!FlagWar.enabled || player.gameMode in listOf(GameMode.CREATIVE, GameMode.SPECTATOR)) return false
        if (!player.isAllowFlying && !player.isFlying) return false
        disable(player)
        return true
    }
}
