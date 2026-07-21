package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect

object NodesPlayerMoveListener {
    private fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val resident = Resident.fromPlayer(player) ?: return
        resident.minimap?.let { minimap ->
            minimap.updateYaw(event.newPosition.yaw)
            minimap.updateWaypointDisplayTransforms(event.newPosition)
        }

        // abort if did not change blocks
        val fromX = player.position.blockX()
        val fromY = player.position.blockY()
        val fromZ = player.position.blockZ()
        val toX = event.newPosition.blockX()
        val toY = event.newPosition.blockY()
        val toZ = event.newPosition.blockZ()
        if (fromX == toX && fromZ == toZ && fromY == toY) {
            return
        }

        // handle event effects

        // player moved -> cancel any home teleport
        resident.teleportThread?.let { thread ->
            thread.cancel()
            resident.teleportThread = null
            Message.error(event.player, "You moved, teleport cancelled")
        }

        // check if player chunk changed
        val fromCoord = Coord.fromBlockCoords(fromX, fromZ)
        val toCoord = Coord.fromBlockCoords(toX, toZ)

        if (fromCoord != toCoord) {
            resident.updateMinimap(toX, toZ)
            onPlayerMoveChunk(event.player, resident, fromCoord, toCoord)
        }
    }

    // handle player teleport (e.g. /t spawn)
    private fun onPlayerTeleport(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return
        val resident = Resident.fromPlayer(player) ?: return
        resident.minimap?.let { minimap ->
            minimap.updateYaw(event.newPosition.yaw)
            minimap.updateWaypointDisplayTransforms(event.newPosition)
        }

        // abort if did not change blocks
        val fromX = player.position.blockX()
        val fromY = player.position.blockY()
        val fromZ = player.position.blockZ()
        val toX = event.newPosition.blockX()
        val toY = event.newPosition.blockY()
        val toZ = event.newPosition.blockZ()
        if (fromX == toX && fromZ == toZ && fromY == toY) {
            return
        }

        // handle event effects

        // check if player chunk changed
        val fromCoord = Coord.fromBlockCoords(fromX, fromZ)
        val toCoord = Coord.fromBlockCoords(toX, toZ)

        if (fromCoord != toCoord) {
            resident.updateMinimap(toX, toZ)
            onPlayerMoveChunk(player, resident, fromCoord, toCoord)
        }
    }

    // handle player changing to new chunk
    private fun onPlayerMoveChunk(player: Player, resident: Resident, fromCoord: Coord, toCoord: Coord) {
        val fromTerritory = Territory.fromCoord(fromCoord)
        val toTerritory = Territory.fromCoord(toCoord)

        if (fromTerritory != null && toTerritory != null) {
            val toTown = toTerritory.town
            val fromTown = fromTerritory.town
            if (toTerritory.name != fromTerritory.name) {
                if (toTown != null) {
                    printTownMessage(player, resident, toTown, toTerritory)
                } else {
                    Message.announcement(player, "${ChatColor.GRAY}${toTerritory.name}")
                }
            } else if (fromTown !== null && toTown !== null) {
                if (toTown !== fromTown || fromTerritory.occupier !== toTerritory.occupier) {
                    printTownMessage(player, resident, toTown, toTerritory)
                }
            } else if (fromTown !== null && toTown === null) {
                Message.announcement(player, "${ChatColor.GRAY}Wilderness")
            } else if (toTown !== null) {
                printTownMessage(player, resident, toTown, toTerritory)
            }
        }

        // check if flight needs to be disabled (e.g. player moved to different town or wilderness)
        // ignore admins in creative and spectator
        if (player.gameMode in listOf(GameMode.CREATIVE, GameMode.SPECTATOR)) return

        val playerTown = Town.fromPlayer(player)

        // if player leaves their own town while flying, disable flight
        if (player.isAllowFlying && toTerritory?.town != playerTown) {
            player.isAllowFlying = false
            // give player slow falling to avoid fall damage
            player.addEffect(Potion(PotionEffect.SLOW_FALLING, 0, 100))
            Message.print(player, "You are no longer in your town, disabling flight")
        }
    }

    fun init() {
        Nodes.eventNode.addListener(PlayerMoveEvent::class.java, this::onPlayerMove)
        Nodes.eventNode.addListener(EntityTeleportEvent::class.java, this::onPlayerTeleport)
    }
}

/**
 * Inputs:
 * player: player who will see message
 * resident: resident from player
 * toTown: territory town the player has entered
 * toTerritory: terretiroy player has entered
 */
private fun printTownMessage(player: Player, resident: Resident, toTown: Town, toTerritory: Territory) {
    val residentTown = resident.town
    val territoryOccupier = toTerritory.occupier

    // territory name token
    val territoryName = if (toTerritory.name != "") {
        "${toTerritory.name} (${toTown.name})"
    } else {
        toTown.name
    }

    // territory name color
    val territoryNameColor = Town.relationshipOfTownToTown(residentTown, toTown).chatColor

    // territory occupation/captured modifier
    val ownerStatus = territoryOccupier?.let { occupier ->
        val relationshipColor = Town.relationshipOfTownToTown(residentTown, occupier).chatColor
        val status = if (occupier === residentTown) "Captured" else "Occupied"
        " $relationshipColor($status)"
    } ?: ""

    Message.announcement(player, "${territoryNameColor}${territoryName}$ownerStatus")
}
