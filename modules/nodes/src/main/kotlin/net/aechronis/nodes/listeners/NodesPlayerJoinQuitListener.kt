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
import net.aechronis.nodes.objects.Nametag
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.TestTownSelection
import net.aechronis.nodes.objects.WaypointMenu
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerRespawnEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.util.UUID

internal object RallyCapAdmission {
    private data class Reservation(
        val nationId: UUID,
        val createdAt: Long,
    )

    private const val RESERVATION_TIMEOUT_MILLIS = 120_000L
    private val reservations: MutableMap<UUID, Reservation> = hashMapOf()

    fun reserve(playerId: UUID): String? = synchronized(reservations) {
        reservations.remove(playerId)
        removeExpiredReservations()
        checkCap(playerId)?.also { return@synchronized it }

        val nation = Resident.fromUuid(playerId)?.nation ?: return@synchronized null
        if (FlagWar.enabled && nation.rallyCap != null) {
            reservations[playerId] = Reservation(nation.uuid, System.currentTimeMillis())
        }
        null
    }

    fun consume(playerId: UUID): String? = synchronized(reservations) {
        reservations.remove(playerId)
        removeExpiredReservations()
        checkCap(playerId)
    }

    fun release(playerId: UUID) {
        synchronized(reservations) {
            reservations.remove(playerId)
        }
    }

    fun reset() {
        synchronized(reservations) {
            reservations.clear()
        }
    }

    private fun checkCap(playerId: UUID): String? {
        if (!FlagWar.enabled) return null
        val nation = Resident.fromUuid(playerId)?.nation ?: return null
        val rallyCap = nation.rallyCap ?: return null
        val onlinePlayerIds = nation.playersOnline.mapTo(hashSetOf()) { player -> player.uuid }
        if (playerId in onlinePlayerIds) return null
        val reservedSlots = reservations.values.count { reservation -> reservation.nationId == nation.uuid }
        if (onlinePlayerIds.size + reservedSlots < rallyCap) return null
        return "${nation.name} has reached its war rally cap of $rallyCap online players"
    }

    private fun removeExpiredReservations() {
        val expiry = System.currentTimeMillis() - RESERVATION_TIMEOUT_MILLIS
        reservations.entries.removeIf { (_, reservation) -> reservation.createdAt < expiry }
    }
}

object NodesPlayerJoinQuitListener {
    fun onPlayerConfiguration(event: AsyncPlayerConfigurationEvent) {
        RallyCapAdmission.reserve(event.player.uuid)?.let { reason ->
            event.player.kick(Component.text(reason))
        }
    }

    fun onPlayerJoin(event: PlayerLoadedEvent) {
        // create resident wrapper for player
        // createResident checks if resident already exists
        val player: Player = event.player
        Resident.create(player)

        val resident: Resident = Resident.fromPlayer(player)!!
        RallyCapAdmission.consume(player.uuid)?.let { reason ->
            player.kick(Component.text(reason))
            return
        }
        Resident.setOnline(resident, player)
        Nametag.onPlayerJoin(player)
        MiningBoostManager.onPlayerJoin(player)
        resident.createMinimap(player)
        Warzone.onPlayerTerritoryChanged(player, net.aechronis.nodes.objects.Territory.fromPlayer(player))
        ModuleScheduler.scheduleNextTick {
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
        ModuleScheduler.scheduleNextTick {
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
        RallyCapAdmission.release(player.uuid)
        val resident = Resident.fromPlayer(player)
        Nametag.onPlayerQuit(player)
        MiningBoostManager.onPlayerQuit(player)
        Warzone.onPlayerQuit(player)
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
        RallyCapAdmission.reset()
        Nodes.eventNode.addListener(AsyncPlayerConfigurationEvent::class.java, this::onPlayerConfiguration)
        Nodes.eventNode.addListener(PlayerLoadedEvent::class.java, this::onPlayerJoin)
        Nodes.eventNode.addListener(PlayerSpawnEvent::class.java, this::onPlayerSpawn)
        Nodes.eventNode.addListener(PlayerRespawnEvent::class.java, this::onPlayerRespawn)
        Nodes.eventNode.addListener(PlayerDeathEvent::class.java, this::onPlayerDeath)
        Nodes.eventNode.addListener(PlayerDisconnectEvent::class.java, this::onPlayerQuit)
    }
}
