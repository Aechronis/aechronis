/**
 * Handle when player join or quit server
 * join: create resident (if does not exist) and mark player online
 * quit: mark player offline
 */

package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.chat.Chat
import net.aechronis.nodes.colonization.Colonization
import net.aechronis.nodes.colonization.ColonizationMenu
import net.aechronis.nodes.objects.MiningBoostManager
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.TestTownSelection
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
        MiningBoostManager.onPlayerJoin(player)
        resident.createMinimap(player)
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            if (player.isOnline) TestTownSelection.showJoinDialog(player, resident)
        }

        // send any active war or colonization progress bars
        if (FlagWar.attackers[player.uuid]?.isNotEmpty() == true) {
            FlagWar.sendWarProgressBarToPlayer(player)
        }

        // add per-player text displays for all active attacks
        for (attack in FlagWar.chunkToAttacker.values) {
            attack.textDisplay.update(player)
        }
    }

    fun onPlayerSpawn(event: PlayerSpawnEvent) {
        Resident.fromPlayer(event.player)?.minimap?.respawn()
    }

    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val respawnPoint = Resident.fromPlayer(player)?.town?.spawnpoint ?: Nodes.config.defaultRespawnPoint
        event.respawnPosition = respawnPoint
        player.respawnPoint = respawnPoint
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            if (player.isOnline) Resident.fromPlayer(player)?.minimap?.respawn()
        }
    }

    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val resident = Resident.fromPlayer(player) ?: return
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
        MiningBoostManager.onPlayerQuit(player)
        if (resident != null) {
            resident.destroyMinimap()
            resident.clearDeathWaypoint()
            Resident.stopPlotSelection(resident)
            Resident.setOffline(resident, player)
        }
        Colonization.clearSelection(player)
        ColonizationMenu.close(player)
        WaypointMenu.close(player)

        // remove player from muting global chat
        Chat.enableGlobalChat(player)

        // remove per-player town name displays for active attacks
        for (attack in FlagWar.chunkToAttacker.values) {
            attack.textDisplay.removePlayerTextDisplay(player)
        }

        // stop any war or colonization attacks owned by the disconnecting player
        val attacks = FlagWar.attackers[player.uuid]?.toList().orEmpty()
        for (attack in attacks) {
            attack.cancel()
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
