package net.aechronis.duels

import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.instance.InstanceContainer
import java.util.UUID

class Team(
    val index: Int,
    val name: String,
    val color: NamedTextColor,
    val members: Set<UUID>,
) {
    val alive: MutableSet<UUID> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<UUID>()
            .apply { addAll(members) }
}

enum class MatchState { COUNTDOWN, ACTIVE, ENDING }

class Match(
    val mode: MatchMode,
    val kit: Kit,
    val map: MapDef,
    val instance: InstanceContainer,
    val teams: List<Team>,
) {
    @Volatile
    var state = MatchState.COUNTDOWN

    val members: List<UUID> get() = teams.flatMap { it.members }

    fun teamOf(uuid: UUID): Team? = teams.firstOrNull { uuid in it.members }

    fun players(): List<Player> =
        members.mapNotNull {
            net.minestom.server.MinecraftServer
                .getConnectionManager()
                .getOnlinePlayerByUuid(it)
        }
}

/**
 * The extension point for new game types (2v2, large events, naval duels): implement this and call
 * [MatchManager.start] with any number of teams of any size.
 */
interface MatchMode {
    val id: String
    val displayName: String

    fun onStart(match: Match) {}

    /** Default rule: the match is over once at most one team still has living players. */
    fun isOver(match: Match): Boolean = match.teams.count { it.alive.isNotEmpty() } <= 1

    fun onEnd(
        match: Match,
        winner: Team?,
    ) {}
}

object DuelMode : MatchMode {
    override val id = "duel"
    override val displayName = "1v1 Duel"
}
