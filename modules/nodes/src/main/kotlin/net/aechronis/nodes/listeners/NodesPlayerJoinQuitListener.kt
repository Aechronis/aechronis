/**
 * Handle when player join or quit server
 * join: create resident (if does not exist) and mark player online
 * quit: mark player offline
 */

package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.chat.Chat
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.WaypointMenu
import net.aechronis.nodes.war.FlagWar
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerRespawnEvent
import net.minestom.server.event.player.PlayerSpawnEvent

object NodesPlayerJoinQuitListener {
    fun onPlayerJoin(event: PlayerLoadedEvent) {
        // create resident wrapper for player
        // createResident checks if resident already exists
        val player: Player = event.player
        Resident.create(player)

        val resident: Resident = Resident.fromPlayer(player)!!
        Resident.setOnline(resident, player)
        resident.createMinimap(player)

        // if war enabled, send active chunk attack progress bars
        if (FlagWar.enabled) {
            FlagWar.sendWarProgressBarToPlayer(player)
        }

        // if war enabled, add per-player text displays for active attacks
        if (FlagWar.enabled) {
            for (attack in FlagWar.chunkToAttacker.values) {
                attack.textDisplay.update(player)
            }
        }
    }

    fun onPlayerSpawn(event: PlayerSpawnEvent) {
        Resident.fromPlayer(event.player)?.minimap?.respawn()
    }

    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            if (player.isOnline) Resident.fromPlayer(player)?.minimap?.respawn()
        }
    }

    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val resident = Resident.fromPlayer(player) ?: return
        resident.town?.let { town -> player.respawnPoint = town.spawnpoint }
        val position = player.position
        resident.recordDeathWaypoint(
            position.blockX(),
            position.blockY(),
            position.blockZ(),
        )
        Message.print(player, "Death waypoint set at ${position.blockX()}, ${position.blockY()}, ${position.blockZ()}")
    }

    fun onPlayerQuit(event: PlayerDisconnectEvent) {
        val player: Player = event.player
        val resident = Resident.fromPlayer(player)
        if (resident != null) {
            resident.destroyMinimap()
            resident.clearDeathWaypoint()
            Resident.stopPlotSelection(resident)
            Resident.setOffline(resident, player)
        }
        WaypointMenu.close(player)

        // remove player from muting global chat
        Chat.enableGlobalChat(player)

        // if war enabled, remove per-player town name displays for active attacks
        if (FlagWar.enabled) {
            for (attack in FlagWar.chunkToAttacker.values) {
                attack.textDisplay.removePlayerTextDisplay(player)
            }
        }

        // if playing attacking a chunk, stop it
        if (FlagWar.enabled) {
            val attacks = FlagWar.attackers[player.uuid]
            if (attacks !== null) {
                for (a in attacks) {
                    a.cancel()
                }
            }
        }
    }

    fun init() {
        Nodes.eventNode.addListener(PlayerLoadedEvent::class.java, this::onPlayerJoin)
        Nodes.eventNode.addListener(PlayerSpawnEvent::class.java, this::onPlayerSpawn)
        Nodes.eventNode.addListener(PlayerRespawnEvent::class.java, this::onPlayerRespawn)
        Nodes.eventNode.addListener(PlayerDeathEvent::class.java, this::onPlayerDeath)
        Nodes.eventNode.addListener(PlayerDisconnectEvent::class.java, this::onPlayerQuit)
    }
}
