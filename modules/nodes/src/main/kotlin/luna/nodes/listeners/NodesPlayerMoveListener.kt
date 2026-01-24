package luna.nodes.listeners

import luna.nodes.utils.ChatColor
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.entity.EntityTeleportEvent
//import org.bukkit.inventory.ItemStack
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.Nodes.getTownFromPlayer
import luna.nodes.objects.Coord
import luna.nodes.objects.Resident
import luna.nodes.objects.Territory
import luna.nodes.objects.Town
import net.minestom.server.event.GlobalEventHandler
import java.beans.EventHandler

object NodesPlayerMoveListener {
    private fun onPlayerMove(event: PlayerMoveEvent) {
        // abort if did not change blocks
        val fromX = event.player.position.blockX()
        val fromY = event.player.position.blockY()
        val fromZ = event.player.position.blockZ()
        val toX = event.newPosition.blockX()
        val toY = event.newPosition.blockY()
        val toZ = event.newPosition.blockZ()
        if (fromX == toX && fromZ == toZ && fromY == toY) {
            return
        }

        // handle event effects
        val player = event.player
        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

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
            onPlayerMoveChunk(event.player, resident, fromCoord, toCoord)
        }
    }

    // handle player teleport (e.g. /t spawn)
    private fun onPlayerTeleport(event: EntityTeleportEvent) {
        val entity = event.entity
        val player = entity as? Player

        if (player == null) {
            return
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

        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        // check if player chunk changed
        val fromCoord = Coord.fromBlockCoords(fromX, fromZ)
        val toCoord = Coord.fromBlockCoords(toX, toZ)

        if (fromCoord != toCoord) {
            onPlayerMoveChunk(player, resident, fromCoord, toCoord)
        }
    }

    // handle player changing to new chunk
    private fun onPlayerMoveChunk(player: Player, resident: Resident, fromCoord: Coord, toCoord: Coord) {
        val fromTerritory = Nodes.getTerritoryFromCoord(fromCoord)
        val toTerritory = Nodes.getTerritoryFromCoord(toCoord)

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

        // update minimap
        resident.updateMinimap(toCoord)

        // check if flight needs to be disabled (e.g. player moved to different town or wilderness)
        // ignore admins in creative and spectator
        if (player.gameMode in listOf(GameMode.CREATIVE, GameMode.SPECTATOR)) return

        val playerTown = getTownFromPlayer(player)

        // if player leaves their own town while flying, disable flight
        if (player.isAllowFlying && toTerritory?.town != playerTown) {
            player.isAllowFlying = false
            // give player slow falling to avoid fall damage
            player.addEffect(Potion(PotionEffect.SLOW_FALLING, 0, 100))
            Message.print(player, "You are no longer in your town, disabling flight")
        }
    }

    fun init(eventHandler: GlobalEventHandler) {
        eventHandler.addListener(PlayerMoveEvent::class.java, this::onPlayerMove)
        eventHandler.addListener(EntityTeleportEvent::class.java, this::onPlayerTeleport)
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
    val territoryNameColor = if (residentTown !== null) {
        val residentNation = residentTown.nation
        val toTownNation = toTown.nation
        if (toTown === residentTown) {
            ChatColor.DARK_GREEN
        } else if (residentNation !== null && toTownNation !== null && residentNation.enemies.contains(toTownNation)) {
            ChatColor.DARK_RED
        } else {
            ChatColor.DARK_AQUA
        }
    } else {
        ChatColor.DARK_AQUA
    }

    // territory occupation/captured modifier
    val ownerStatus = if (residentTown !== null && territoryOccupier !== null) {
        if (territoryOccupier === residentTown) {
            " ${ChatColor.DARK_GREEN}(Captured)"
        } else if (toTown === residentTown) {
            " ${ChatColor.DARK_RED}(Occupied)"
        } else {
            " ${ChatColor.DARK_AQUA}(Occupied)"
        }
    } else {
        if (territoryOccupier !== null) {
            " (Occupied)"
        } else {
            ""
        }
    }

    Message.announcement(player, "${territoryNameColor}${territoryName}$ownerStatus")
}
