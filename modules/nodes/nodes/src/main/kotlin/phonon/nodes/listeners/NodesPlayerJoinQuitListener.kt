/**
 * Handle when player join or quit server
 * join: create resident (if does not exist) and mark player online
 * quit: mark player offline
 */

package phonon.nodes.listeners

import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import phonon.nodes.Nodes
import phonon.nodes.chat.Chat
//import phonon.nodes.objects.Nametag
import phonon.nodes.objects.Resident

public fun onPlayerConfiguration(event: AsyncPlayerConfigurationEvent) {
    val player = event.getPlayer()
    val instance = MinecraftServer.getInstanceManager().instances.first()
    event.spawningInstance = instance
    player.respawnPoint = Pos(0.0, 100.0, 0.0)
}

public fun onPlayerJoin(event: PlayerLoadedEvent) {
    // create resident wrapper for player
    // createResident checks if resident already exists
    val player: Player = event.getPlayer()
    Nodes.createResident(player)

    val resident: Resident = Nodes.getResident(player)!!
    Nodes.setResidentOnline(resident, player)

//    // if war enabled, send active chunk attack progress bars
//        if (Nodes.war.enabled == true) {
//            Nodes.war.sendWarProgressBarToPlayer(player)
//        }

//    // update nametags
//        Nametag.pipelinedUpdateAllText()

    // update username -> uuid cache
    Nodes.playerNameCache.set(player.uuid,player.username)
}

public fun onPlayerQuit(event: PlayerDisconnectEvent) {
    val player: Player = event.getPlayer()
    val resident = Nodes.getResident(player)
    if (resident != null) {
//            resident.destroyMinimap()
        Nodes.setResidentOffline(resident, player)
    }

    // remove player from muting global chat
    Chat.enableGlobalChat(player)

//        // if playing attacking a chunk, stop it
//        if (Nodes.war.enabled) {
//            val attacks = Nodes.war.attackers.get(player.getUniqueId())
//            if (attacks !== null) {
//                for (a in attacks) {
//                    a.cancel()
//                }
//            }
//        }
}
