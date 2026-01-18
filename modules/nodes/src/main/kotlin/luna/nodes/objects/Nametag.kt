/**
 * 1.13+ Player town nametag
 *
 * NOTE: this conflicts with any other plugin doing nametag prefix/suffix (e.g. TAB)
 * Make sure all other plugins that affect prefix/suffix are disabled
 *
 * TODO: make sure name is not too long (may cause bukkit error)
 */

package luna.nodes.objects
import luna.nodes.Nodes
import net.minestom.server.entity.Player

/**
 * Get town nametag text as VIEWED by input player
 */
fun townNametagViewedByPlayer(
    town: Town,
    viewer: Player,
    space: Boolean = true, // append space to the end of string
): String {
    // get input player relation to this.player
    val otherTown = Nodes.getResident(viewer)?.town
    if (otherTown !== null) {
        val townNation = town.nation
        val otherNation = otherTown.nation
        if (town === otherTown) {
            return if (space) "${town.nametagTown} " else town.nametagTown
        } else if (townNation !== null && townNation === otherNation) {
            return if (space) "${town.nametagNation} " else town.nametagNation
        } else if (townNation !== null && otherNation !== null && townNation.allies.contains(otherNation)) {
            return if (space) "${town.nametagAlly} " else town.nametagAlly
        } else if (townNation !== null && otherNation !== null && townNation.enemies.contains(otherNation)) {
            return if (space) "${town.nametagEnemy} " else town.nametagEnemy
        }
    }

    return if (space) "${town.nametagNeutral} " else town.nametagNeutral
}

//public object Nametag {
//
//    // lock for pipelined nametag update
//    private var updateLock: Boolean = false
//
//    /**
//     * Update nametag text for player
//     * Sends team packets directly to the client
//     */
//    public fun updateTextForPlayer(player: Player) {
//        // unregister towns
//        for (town in Nodes.towns.values) {
//            val townNametagId = "t${town.townNametagId}"
//            try {
//                player.sendTeamRemove(townNametagId)
//            } catch (e: Exception) {
//                // ignore if team doesn't exist
//            }
//        }
//
//        // re create teams from town names
//        for (town in Nodes.towns.values) {
//            val townNametagId = "t${town.townNametagId}"
//            val prefix = townNametagViewedByPlayer(town, player)
//
//            // create the team with town prefix
//            player.sendTeamCreate(townNametagId, prefix, "")
//        }
//
//        // add other players to teams
//        for (otherPlayer in Bukkit.getOnlinePlayers()) {
//            val town = Nodes.getTownFromPlayer(otherPlayer)
//            if (town !== null) {
//                val townNametagId = "t${town.townNametagId}"
//                player.sendTeamAddPlayers(townNametagId, listOf(otherPlayer.name))
//            }
//        }
//    }
//
//    /**
//     * Update all player nametags using a pipeline:
//     * - only update subset of online players each time
//     */
//    public fun pipelinedUpdateAllText() {
//        if (Nametag.updateLock == true) {
//            return
//        }
//
//        val onlinePlayers = Bukkit.getOnlinePlayers().toList()
//        if (onlinePlayers.size <= 0) {
//            return
//        }
//
//        Nametag.updateLock = true
//
//        val updatesPerTick: Int = Math.max(1, Math.ceil(onlinePlayers.size.toDouble() / Config.nametagPipelineTicks.toDouble()).toInt())
//        var index = 0
//        var tickOffset = 1L // folia requires delay > 0
//
//        while (index < onlinePlayers.size) {
//            val idxStart = index
//            val idxEnd = Math.min(index + updatesPerTick, onlinePlayers.size)
//            Bukkit.getGlobalRegionScheduler().runDelayed(
//                Nodes.plugin!!,
//                { _ ->
//                    for (i in idxStart until idxEnd) {
//                        val player = onlinePlayers[i]
//                        if (player.isOnline()) {
//                            Nametag.updateTextForPlayer(player)
//                        }
//                    }
//                },
//                tickOffset,
//            )
//
//            index += updatesPerTick
//            tickOffset += 1L
//        }
//
//        // finish after next tick
//        Bukkit.getGlobalRegionScheduler().runDelayed(
//            Nodes.plugin!!,
//            { _ ->
//                Nametag.updateLock = false
//            },
//            tickOffset,
//        )
//    }
//}
