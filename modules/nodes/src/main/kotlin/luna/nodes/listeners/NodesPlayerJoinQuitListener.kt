/**
 * Handle when player join or quit server
 * join: create resident (if does not exist) and mark player online
 * quit: mark player offline
 */

package luna.nodes.listeners

import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import luna.nodes.Nodes
import luna.nodes.chat.Chat
import luna.nodes.objects.Resident
import luna.nodes.war.FlagWar

object NodesPlayerJoinQuitListener {
    fun onPlayerJoin(event: PlayerLoadedEvent) {
        // create resident wrapper for player
        // createResident checks if resident already exists
        val player: Player = event.player
        Nodes.createResident(player)

        val resident: Resident = Nodes.getResident(player)!!
        Nodes.setResidentOnline(resident, player)

        // if war enabled, send active chunk attack progress bars
        if (Nodes.war.enabled) {
            Nodes.war.sendWarProgressBarToPlayer(player)
        }

        // if war enabled, add per-player text displays for active attacks
        if (Nodes.war.enabled) {
            for (attack in FlagWar.chunkToAttacker.values) {
                attack.textDisplay.update(player)
            }
        }
    }

    fun onPlayerQuit(event: PlayerDisconnectEvent) {
        val player: Player = event.player
        val resident = Nodes.getResident(player)
        if (resident != null) {
            resident.destroyMinimap()
            Nodes.setResidentOffline(resident, player)
        }

        // remove player from muting global chat
        Chat.enableGlobalChat(player)

        // if war enabled, remove per-player town name displays for active attacks
        if (Nodes.war.enabled) {
            for (attack in FlagWar.chunkToAttacker.values) {
                attack.textDisplay.removePlayerTextDisplay(player)
            }
        }

        // if playing attacking a chunk, stop it
        if (Nodes.war.enabled) {
            val attacks = Nodes.war.attackers.get(player.uuid)
            if (attacks !== null) {
                for (a in attacks) {
                    a.cancel()
                }
            }
        }
    }

    fun init() {
        Nodes.eventNode.addListener(PlayerLoadedEvent::class.java, this::onPlayerJoin)
        Nodes.eventNode.addListener(PlayerDisconnectEvent::class.java, this::onPlayerQuit)
    }
}
