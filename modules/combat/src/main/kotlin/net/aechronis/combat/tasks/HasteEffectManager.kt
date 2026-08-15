package net.aechronis.combat.tasks

import net.minestom.server.entity.Player
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect

object HasteEffectManager {
    private val requests = HashMap<Player, MutableMap<String, Potion>>()
    private val applied = HashMap<Player, Potion>()

    @Synchronized
    fun set(
        player: Player,
        source: String,
        amplifier: Int,
        durationTicks: Int,
    ) {
        require(amplifier >= 0) { "Haste amplifier must not be negative" }
        require(durationTicks > 0 || durationTicks == Potion.INFINITE_DURATION) {
            "Haste duration must be positive or infinite"
        }

        requests.getOrPut(player, ::HashMap)[source] =
            Potion(PotionEffect.HASTE, amplifier, durationTicks)
        applyStrongest(player)
    }

    @Synchronized
    fun clear(
        player: Player,
        source: String,
    ) {
        val playerRequests = requests[player] ?: return
        playerRequests.remove(source)
        if (playerRequests.isEmpty()) requests.remove(player)
        applyStrongest(player)
    }

    @Synchronized
    fun clearPlayer(player: Player) {
        requests.remove(player)
        if (applied.remove(player) != null) player.removeEffect(PotionEffect.HASTE)
    }

    private fun applyStrongest(player: Player) {
        val strongest =
            requests[player]
                ?.values
                ?.maxWithOrNull(
                    compareBy<Potion> { it.amplifier() }
                        .thenBy { if (it.duration() == Potion.INFINITE_DURATION) Int.MAX_VALUE else it.duration() },
                )

        if (strongest == null) {
            if (applied.remove(player) != null) player.removeEffect(PotionEffect.HASTE)
            return
        }
        if (applied[player] == strongest) return

        applied[player] = strongest
        player.addEffect(strongest)
    }
}
