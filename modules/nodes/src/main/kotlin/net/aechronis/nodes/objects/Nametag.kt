/**
 * Player town nametag
 */

package net.aechronis.nodes.objects

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.war.FlagWar
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.color.TeamColor
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.play.TeamsPacket
import java.util.UUID

private fun townRelationshipViewedByPlayer(town: Town, viewer: Player): DiplomaticRelationship = Town.relationshipOfTownToTown(
    town,
    Resident.fromPlayer(viewer)?.town,
)

private fun townNametagForRelationship(
    town: Town,
    relationship: DiplomaticRelationship,
    space: Boolean,
): String {
    val text = when (relationship) {
        DiplomaticRelationship.TOWN -> town.nametagTown
        DiplomaticRelationship.NATION -> town.nametagNation
        DiplomaticRelationship.ALLY -> town.nametagAlly
        DiplomaticRelationship.ENEMY -> town.nametagEnemy
        DiplomaticRelationship.NEUTRAL -> town.nametagNeutral
    }
    return if (space) "$text " else text
}

/**
 * Get town nametag text as VIEWED by input player
 */
fun townNametagViewedByPlayer(
    town: Town,
    viewer: Player,
    space: Boolean = true, // append space to the end of string
): String = townNametagForRelationship(town, townRelationshipViewedByPlayer(town, viewer), space)

object Nametag {
    internal class ViewerState(
        val relationships: MutableMap<Int, DiplomaticRelationship> = hashMapOf(),
    ) {
        private val memberTeams: MutableMap<String, Int> = hashMapOf()

        fun createTown(
            townId: Int,
            relationship: DiplomaticRelationship,
            members: Collection<String>,
        ) {
            relationships[townId] = relationship
            members.forEach { member -> memberTeams[member] = townId }
        }

        fun removeTown(townId: Int): Boolean {
            if (relationships.remove(townId) == null) return false
            memberTeams.entries.removeIf { (_, memberTownId) -> memberTownId == townId }
            return true
        }

        fun addMember(townId: Int, member: String): Boolean {
            if (!relationships.containsKey(townId) || memberTeams[member] == townId) return false
            memberTeams[member] = townId
            return true
        }

        fun removeMember(townId: Int, member: String): Boolean {
            if (memberTeams[member] != townId) return false
            memberTeams.remove(member)
            return true
        }
    }

    private val viewers: MutableMap<UUID, ViewerState> = hashMapOf()
    private var started: Boolean = false

    fun start() {
        if (started) return
        started = true

        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
        viewers.keys.retainAll(onlinePlayers.mapTo(hashSetOf()) { it.uuid })
        onlinePlayers.forEach { player ->
            if (viewers[player.uuid] == null) initializeViewer(player)
        }
    }

    fun stop() {
        started = false
    }

    /** Initialize every town team for a newly connected viewer. */
    internal fun onPlayerJoin(player: Player) {
        if (!started || viewers.containsKey(player.uuid)) return

        initializeViewer(player)
        Town.fromPlayer(player)?.let { town ->
            addMember(
                town,
                player.username,
                excludedViewer = player.uuid,
            )
        }
    }

    internal fun onPlayerQuit(player: Player) {
        if (started) {
            Town.fromPlayer(player)?.let { town ->
                removeMember(
                    town,
                    player.username,
                    excludedViewer = player.uuid,
                )
            }
        }
        viewers.remove(player.uuid)
    }

    internal fun onResidentAdded(town: Town, player: Player) {
        if (!started) return
        addMember(town, player.username)
        refreshViewerRelationships(player)
    }

    internal fun onResidentRemoved(town: Town, player: Player) {
        if (!started) return
        removeMember(town, player.username)
        refreshViewerRelationships(player)
    }

    internal fun onTownCreated(town: Town) {
        if (!started) return
        val members = onlineMembers(town)
        initializedOnlineViewers().forEach { (viewer, state) ->
            createTownTeam(viewer, state, town, members)
        }
    }

    internal fun onTownDestroyed(town: Town) {
        if (!started) return
        initializedOnlineViewers().forEach { (viewer, state) ->
            if (state.removeTown(town.townNametagId)) {
                viewer.sendPacket(TeamsPacket(teamName(town), TeamsPacket.RemoveTeamAction()))
            }
        }
        refreshRelationships()
    }

    internal fun onTownRenamed(town: Town) {
        if (!started) return
        initializedOnlineViewers().forEach { (viewer, state) ->
            val relationship = state.relationships[town.townNametagId] ?: return@forEach
            viewer.sendPacket(updateTeamPacket(town, relationship))
        }
    }

    internal fun refreshRelationships() {
        if (!started) return
        initializedOnlineViewers().forEach { (viewer, _) -> refreshViewerRelationships(viewer) }
        FlagWar.refreshAttackTextDisplays()
    }

    internal fun rebuildAllViewers() {
        if (!started) return

        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
        viewers.keys.retainAll(onlinePlayers.mapTo(hashSetOf()) { it.uuid })
        onlinePlayers.forEach { player ->
            viewers.remove(player.uuid)?.relationships?.keys?.forEach { townId ->
                player.sendPacket(TeamsPacket(teamName(townId), TeamsPacket.RemoveTeamAction()))
            }
            initializeViewer(player)
        }
    }

    private fun initializeViewer(player: Player) {
        val state = ViewerState()
        viewers[player.uuid] = state
        Nodes.towns.values.forEach { town ->
            createTownTeam(player, state, town, onlineMembers(town))
        }
    }

    private fun createTownTeam(
        viewer: Player,
        state: ViewerState,
        town: Town,
        members: List<String>,
    ) {
        val relationship = townRelationshipViewedByPlayer(town, viewer)
        state.createTown(town.townNametagId, relationship, members)
        viewer.sendPacket(
            TeamsPacket(
                teamName(town),
                TeamsPacket.CreateTeamAction(settings(town, relationship), members),
            ),
        )
    }

    private fun refreshViewerRelationships(viewer: Player) {
        val state = viewers[viewer.uuid] ?: return
        Nodes.towns.values.forEach { town ->
            val relationship = townRelationshipViewedByPlayer(town, viewer)
            val previous = state.relationships.put(town.townNametagId, relationship)
            when {
                previous == null -> createTownTeam(viewer, state, town, onlineMembers(town))
                previous != relationship -> viewer.sendPacket(updateTeamPacket(town, relationship))
            }
        }
    }

    private fun addMember(
        town: Town,
        member: String,
        excludedViewer: UUID? = null,
    ) {
        val packet = TeamsPacket(teamName(town), TeamsPacket.AddEntitiesToTeamAction(listOf(member)))
        initializedOnlineViewers().forEach { (viewer, state) ->
            if (viewer.uuid != excludedViewer && state.addMember(town.townNametagId, member)) {
                viewer.sendPacket(packet)
            }
        }
    }

    private fun removeMember(
        town: Town,
        member: String,
        excludedViewer: UUID? = null,
    ) {
        val packet = TeamsPacket(teamName(town), TeamsPacket.RemoveEntitiesToTeamAction(listOf(member)))
        initializedOnlineViewers().forEach { (viewer, state) ->
            if (viewer.uuid != excludedViewer && state.removeMember(town.townNametagId, member)) {
                viewer.sendPacket(packet)
            }
        }
    }

    private fun initializedOnlineViewers(): List<Pair<Player, ViewerState>> = MinecraftServer
        .getConnectionManager().onlinePlayers.mapNotNull { player ->
            viewers[player.uuid]?.let { state -> player to state }
        }

    private fun onlineMembers(town: Town): List<String> = MinecraftServer.getConnectionManager().onlinePlayers
        .filter { player -> Town.fromPlayer(player) === town }
        .map(Player::getUsername)

    private fun updateTeamPacket(town: Town, relationship: DiplomaticRelationship): TeamsPacket = TeamsPacket(
        teamName(town),
        TeamsPacket.UpdateTeamAction(settings(town, relationship)),
    )

    private fun settings(town: Town, relationship: DiplomaticRelationship): TeamsPacket.Settings = TeamsPacket.Settings(
        Component.text(teamName(town)),
        Component.text(townNametagForRelationship(town, relationship, space = true)),
        Component.empty(),
        TeamsPacket.NameTagVisibility.ALWAYS,
        TeamsPacket.CollisionRule.ALWAYS,
        TeamColor.WHITE,
        0,
    )

    private fun teamName(town: Town): String = teamName(town.townNametagId)

    private fun teamName(townId: Int): String = "t$townId"
}
