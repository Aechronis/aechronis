package phonon.nodes.listeners

import org.bukkit.ChatColor
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.entity.EntityTeleportEvent
//import org.bukkit.inventory.ItemStack
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.Nodes.getTownFromPlayer
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Territory
import phonon.nodes.objects.Town

public fun onPlayerMove(event: PlayerMoveEvent) {
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
//    if (resident?.teleportThread != null) {
//        resident.teleportThread!!.cancel()
//        resident.teleportThread = null // remove reference
//        Message.error(event.player, "You moved, teleport cancelled")
//
//        // provide cost refund if player teleporting to outpost
//        if (resident.isTeleportingToOutpost == true) {
//            Message.error(event.player, "Refunding outpost teleport items")
//
//            val world = player.world
//            val location = player.location
//            val inventory = player.getInventory()
//
//            for ((material, amount) in Config.outpostTeleportCost) {
//                val items = ItemStack(material, amount)
//                val leftover = inventory.addItem(items)
//
//                // drop remaining items at player
//                for (items in leftover.values) {
//                    world.dropItem(location, items)
//                }
//            }
//
//            resident.isTeleportingToOutpost = false
//        }
//
//        // provide cost refund if player teleporting to a nation town
//        if (resident.isTeleportingToNationTown == true) {
//            Message.error(event.player, "Refunding teleport items")
//
//            val world = player.world
//            val location = player.location
//            val inventory = player.getInventory()
//
//            for ((material, amount) in Config.nationTownTeleportCost) {
//                val items = ItemStack(material, amount)
//                val leftover = inventory.addItem(items)
//
//                // drop remaining items at player
//                for (items in leftover.values) {
//                    world.dropItem(location, items)
//                }
//            }
//
//            resident.isTeleportingToNationTown = false
//        }
//    }

    // check if player chunk changed
    val fromCoord = Coord.fromBlockCoords(fromX, fromZ)
    val toCoord = Coord.fromBlockCoords(toX, toZ)

    if (fromCoord != toCoord) {
        onPlayerMoveChunk(event.player, resident, fromCoord, toCoord)
    }
}

// handle player teleport (e.g. /t spawn)
public fun onPlayerTeleport(event: EntityTeleportEvent) {
    val entity = event.entity
    val player = if (entity is Player) entity else null

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
public fun onPlayerMoveChunk(player: Player, resident: Resident, fromCoord: Coord, toCoord: Coord) {
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
            if (toTerritory.name != fromTerritory.name) {
                Message.announcement(player, "${ChatColor.GRAY}${toTerritory.name}")
            } else {
                Message.announcement(player, "${ChatColor.GRAY}Wilderness")
            }
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
        "${toTown.name}"
    }

    // territory name color and territory occupation/captured modifier
    var territoryNameColor = ""
    var ownerStatus = ""

    if (residentTown !== null) {
        if (toTown === residentTown) {
            territoryNameColor = "${ChatColor.DARK_GREEN}"
        } else if (residentTown.enemies.contains(toTown)) {
            territoryNameColor = "${ChatColor.DARK_RED}"
        } else {
            territoryNameColor = "${ChatColor.DARK_AQUA}"
        }

        // set occupation status
        if (territoryOccupier !== null) {
            if (territoryOccupier === residentTown) {
                ownerStatus = " ${ChatColor.DARK_GREEN}(Captured)"
            } else if (toTown === residentTown) {
                ownerStatus = " ${ChatColor.DARK_RED}(Occupied)"
            } else {
                ownerStatus = " ${ChatColor.DARK_AQUA}(Occupied)"
            }
        }
    } else {
        territoryNameColor = "${ChatColor.DARK_AQUA}"
        if (territoryOccupier !== null) {
            ownerStatus = " (Occupied)"
        }
    }

    Message.announcement(player, "${territoryNameColor}${territoryName}$ownerStatus")
}
