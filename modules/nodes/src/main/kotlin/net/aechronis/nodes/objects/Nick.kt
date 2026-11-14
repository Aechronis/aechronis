package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.utils.VisibilityRules
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket
import net.minestom.server.network.packet.server.play.TeamsPacket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Nicknames hide a player's username from everyone else: chat, the tab list and the name above
 * their head. The profile name is rewritten in outgoing player-info packets and the town nametag
 * team entries are rewritten to match, so the town prefix still applies. The nicked player keeps
 * seeing their own real name.
 */
object Nick {
    const val PERMISSION = "nodes.nick"
    private const val REFRESH_RULE = "nodes:nick-refresh"
    private val VALID_NAME = Regex("[A-Za-z0-9_]{3,16}")

    private data class Entry(val realName: String, val nick: String)

    // read from network threads, so mirrored out of the resident registry
    private val byUuid = ConcurrentHashMap<UUID, Entry>()
    private val byRealName = ConcurrentHashMap<String, String>()

    fun init() {
        Nodes.eventNode.addListener(PlayerPacketOutEvent::class.java, ::onPacketOut)
    }

    internal fun track(resident: Resident) {
        byUuid.remove(resident.uuid)?.let { byRealName.remove(it.realName) }
        val nick = resident.nickname ?: return
        // team entries use the live username, which can differ from the saved resident name
        val realName = resident.player()?.username ?: resident.name
        byUuid[resident.uuid] = Entry(realName, nick)
        byRealName[realName] = nick
    }

    internal fun clear() {
        byUuid.clear()
        byRealName.clear()
    }

    /** Returns an error message, or null when [nick] may be used by [resident]. */
    internal fun validate(resident: Resident, nick: String): String? {
        if (!VALID_NAME.matches(nick)) return "Nicknames must be 3-16 letters, numbers or underscores"
        val takenByResident = Resident.all().any { other ->
            other !== resident && (other.name.equals(nick, true) || other.nickname.equals(nick, true))
        }
        val takenByPlayer = MinecraftServer.getConnectionManager().onlinePlayers.any { player ->
            player.uuid != resident.uuid && player.username.equals(nick, true)
        }
        return if (takenByResident || takenByPlayer) "That name belongs to another player" else null
    }

    fun set(player: Player, resident: Resident, nick: String?) {
        val wanted = nick?.takeUnless { it.equals(resident.name, true) }
        if (wanted != null) {
            validate(resident, wanted)?.let { error ->
                Message.error(player, error)
                return
            }
        }
        if (wanted == resident.nickname) {
            Message.print(player, if (wanted == null) "You have no nickname" else "You are already nicked as $wanted")
            return
        }
        resident.setNickname(wanted)
        refresh(player)
        Message.print(player, if (wanted == null) "Nickname removed" else "Others now see you as $wanted")
    }

    /** Respawns [player] for every viewer so clients pick up the rewritten profile name. */
    private fun refresh(player: Player) {
        VisibilityRules.set(player, REFRESH_RULE) { false }
        VisibilityRules.remove(player, REFRESH_RULE)
        Nametag.rebuildAllViewers()
    }

    private fun onPacketOut(event: PlayerPacketOutEvent) {
        if (byUuid.isEmpty() || event.isCancelled) return
        val replacement = when (val packet = event.packet) {
            is PlayerInfoUpdatePacket -> rewriteInfo(packet, event.player)
            is TeamsPacket -> rewriteTeam(packet, event.player)
            else -> null
        } ?: return
        event.isCancelled = true
        val viewer = event.player
        viewer.sendPacket(replacement)
        if (replacement !is PlayerInfoUpdatePacket) return
        // Minestom fires this event while draining the send queue, so the replacement lands after
        // any spawn packet already queued behind the original and the client drops that spawn.
        // Respawn the nicked players for this viewer once the rewritten profile is ahead of it.
        val nicked = replacement.entries().mapNotNull { entry -> entry.uuid().takeIf(byUuid::containsKey) }
        ModuleScheduler.scheduleNextTick {
            nicked.forEach { uuid ->
                val target = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid) ?: return@forEach
                if (viewer !in target.viewers) return@forEach
                target.updateOldViewer(viewer)
                target.updateNewViewer(viewer)
            }
        }
    }

    private fun rewriteInfo(packet: PlayerInfoUpdatePacket, viewer: Player): PlayerInfoUpdatePacket? {
        if (PlayerInfoUpdatePacket.Action.ADD_PLAYER !in packet.actions()) return null
        var changed = false
        val entries = packet.entries().map { entry ->
            val nick = byUuid[entry.uuid()]?.nick
            if (nick == null || entry.uuid() == viewer.uuid || entry.username() == nick) return@map entry
            changed = true
            PlayerInfoUpdatePacket.Entry(
                entry.uuid(),
                nick,
                entry.properties(),
                entry.listed(),
                entry.latency(),
                entry.gameMode(),
                entry.displayName(),
                entry.chatSession(),
                entry.listOrder(),
                entry.displayHat(),
            )
        }
        return if (changed) PlayerInfoUpdatePacket(packet.actions(), entries) else null
    }

    private fun rewriteTeam(packet: TeamsPacket, viewer: Player): TeamsPacket? {
        val action = when (val action = packet.action()) {
            is TeamsPacket.CreateTeamAction -> renamed(action.entities(), viewer)?.let { TeamsPacket.CreateTeamAction(action.settings(), it) }
            is TeamsPacket.AddEntitiesToTeamAction -> renamed(action.entities(), viewer)?.let { TeamsPacket.AddEntitiesToTeamAction(it) }
            is TeamsPacket.RemoveEntitiesToTeamAction -> renamed(action.entities(), viewer)?.let { TeamsPacket.RemoveEntitiesToTeamAction(it) }
            else -> null
        } ?: return null
        return TeamsPacket(packet.teamName(), action)
    }

    private fun renamed(entities: List<String>, viewer: Player): List<String>? {
        if (entities.none { it != viewer.username && byRealName.containsKey(it) }) return null
        return entities.map { if (it == viewer.username) it else byRealName[it] ?: it }
    }
}

class NickCommand : NodesCommand("nick", Nick.PERMISSION) {
    init {
        setDefaultExecutor { player, resident, _ -> Nick.set(player, resident, null) }
        val name = ArgumentType.Word("name")
        addSyntax({ player, resident, context -> Nick.set(player, resident, context[name]) }, name)
    }
}
