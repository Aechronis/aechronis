/**
 * Player town nametag
 */

package luna.nodes.objects
import luna.nodes.Nodes
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.play.TeamsPacket
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule

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

object Nametag {
    private var task: Task? = null

    /**
     * Start the automatic nametag update scheduler
     */
    fun start(period: Long) {
        if (this.task !== null) {
            return
        }

        val runnable = Runnable {
            updateAllText()
        }

        this.task = MinecraftServer.getSchedulerManager()
            .buildTask(runnable)
            .delay(TaskSchedule.millis(period))
            .repeat(TaskSchedule.millis(period))
            .schedule()
    }

    /**
     * Stop the automatic nametag update scheduler
     */
    fun stop() {
        val task = this.task
        if (task === null) {
            return
        }

        task.cancel()
        this.task = null
    }

    /**
     * Update nametag text for player
     * Sends team packets directly to the player so they see customized prefixes
     */
    private fun updateTextForPlayer(player: Player) {
        // remove all existing town teams for this viewer
        for (town in Nodes.towns.values) {
            val teamName = "t${town.townNametagId}"
            player.sendPacket(TeamsPacket(teamName, TeamsPacket.RemoveTeamAction()))
        }

        // create teams for each town with prefix as viewed by this player
        for (town in Nodes.towns.values) {
            val teamName = "t${town.townNametagId}"
            val prefix = townNametagViewedByPlayer(town, player, space = true)

            // collect all players in this town to add to the team
            val townMembers = mutableListOf<String>()
            for (otherPlayer in MinecraftServer.getConnectionManager().onlinePlayers) {
                val otherTown = Nodes.getTownFromPlayer(otherPlayer)
                if (otherTown === town) {
                    townMembers.add(otherPlayer.username)
                }
            }

            // create team with customized prefix for this viewer
            val createAction = TeamsPacket.CreateTeamAction(
                Component.text(teamName), // displayName
                0, // friendlyFlags (0 = friendly fire enabled)
                TeamsPacket.NameTagVisibility.ALWAYS, // nameTagVisibility
                TeamsPacket.CollisionRule.ALWAYS, // collisionRule
                net.kyori.adventure.text.format.NamedTextColor.WHITE, // teamColor
                Component.text(prefix), // teamPrefix
                Component.empty(), // teamSuffix
                townMembers // entities (players in this town)
            )
            player.sendPacket(TeamsPacket(teamName, createAction))
        }
    }

    /**
     * Update all player nametags
     * Calls updateTextForPlayer for each online player
     */
    private fun updateAllText() {
        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
        for (player in onlinePlayers) {
            updateTextForPlayer(player)
        }
    }
}
