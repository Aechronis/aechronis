package net.aechronis.nodes.war

import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.server.hasPermission
import net.kyori.adventure.text.Component
import java.util.UUID

/**
 * Residents of a town whose home territory is occupied in a WAR attack are kept off the server.
 * Nothing is stored: the ban is derived from live occupation state, so it lifts the moment the
 * home is released (recaptured) or the war ends.
 */
internal object HomeCaptureBan {
    const val EXEMPT_PERMISSION = "nodes.war.banexempt"

    private fun isHomeCaptured(town: Town): Boolean {
        if (!FlagWar.enabled) return false
        val home = Territory.fromId(town.home) ?: return false
        return home.occupier != null && !FlagWar.isColonized(home.core) && !Warzone.isRegistered(home)
    }

    /** Kick reason if [playerId] must not be online right now, else null. */
    fun reason(playerId: UUID): String? {
        val town = Resident.fromUuid(playerId)?.town ?: return null
        if (!isHomeCaptured(town) || playerId.hasPermission(EXEMPT_PERMISSION)) return null
        return "${town.name}'s home was captured. You are banned until it is liberated or the war ends."
    }

    fun kickResidents(town: Town) {
        for (resident in town.residents) {
            val player = resident.player() ?: continue
            reason(player.uuid)?.let { player.kick(Component.text(it)) }
        }
    }
}
