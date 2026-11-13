package net.aechronis.punishments

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerCommandEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object Enforcement {
    const val SITE = "bans.aechronis.net"

    /** Private-message commands a muted player cannot use. */
    private val MESSAGE_COMMANDS = setOf("message", "msg", "tell", "whisper", "w", "reply", "r")

    /** Mute expiry per online player; [Long.MAX_VALUE] is permanent. */
    private val mutes = ConcurrentHashMap<UUID, Long>()

    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        val profile = event.gameProfile
        val ip = (event.connection.remoteAddress as? InetSocketAddress)?.address?.hostAddress
        try {
            val repository = Punishments.repository
            val active = repository.active(profile.uuid())
            val blocks = active.filter { it.type.blocksLogin }.ifEmpty { listOfNotNull(ip?.let(repository::activeIpBan)) }
            if (blocks.isNotEmpty()) {
                event.connection.kick(kickMessage(blocks))
                return
            }
            repository.remember(profile.uuid(), profile.name(), ip)
            setMute(profile.uuid(), active)
        } catch (error: Exception) {
            // Failing open keeps the server joinable if the database breaks; the error is loud.
            System.err.println("[Punishments] Login check failed for ${profile.name()}: $error")
        }
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        mutes.remove(event.player.uuid)
        PunishMenu.forget(event.player.uuid)
    }

    fun onChat(event: PlayerChatEvent) {
        // Staff answering a /punish prompt aren't chatting, even while muted.
        if (PunishMenu.answer(event.player, event.rawMessage)) {
            event.isCancelled = true
            return
        }
        if (muted(event.player)) event.isCancelled = true else Evidence.heard(event.player.uuid, event.rawMessage)
    }

    fun onCommand(event: PlayerCommandEvent) {
        val name =
            event.command
                .trimStart('/')
                .substringBefore(' ')
                .lowercase()
        if (name in MESSAGE_COMMANDS && muted(event.player)) event.isCancelled = true
    }

    private fun muted(player: Player): Boolean {
        val expiry = mutes[player.uuid] ?: return false
        if (expiry <= System.currentTimeMillis()) {
            mutes.remove(player.uuid, expiry)
            return false
        }
        player.sendMessage(Component.text("You are muted${remaining(expiry)}.", NamedTextColor.RED))
        return true
    }

    private fun setMute(
        uuid: UUID,
        active: List<Record>,
    ) {
        val mutesActive = active.filter { it.type == Type.MUTE }
        if (mutesActive.isEmpty()) {
            mutes.remove(uuid)
        } else {
            mutes[uuid] = mutesActive.maxOf { it.expiresAt ?: Long.MAX_VALUE }
        }
    }

    /** Restores mutes of players already online, e.g. after a module reload. Blocking. */
    fun loadOnline() = MinecraftServer.getConnectionManager().onlinePlayers.forEach { refreshMute(it.uuid) }

    /** Re-reads the player's mutes after a lift. Blocking. */
    fun refreshMute(uuid: UUID) {
        if (MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid) == null) return
        setMute(uuid, Punishments.repository.active(uuid))
    }

    fun issued(
        records: List<Record>,
        ip: String?,
    ) {
        val connections = MinecraftServer.getConnectionManager()
        records.forEach { record ->
            val player = connections.getOnlinePlayerByUuid(record.uuid)
            when {
                record.type == Type.IP_BAN && ip != null -> {
                    connections.onlinePlayers
                        .filter { (it.playerConnection.remoteAddress as? InetSocketAddress)?.address?.hostAddress == ip }
                        .forEach { it.kick(kickMessage(listOf(record))) }
                }
                // Stacked bans show every reason.
                record.type.blocksLogin ->
                    player?.kick(
                        kickMessage(Punishments.repository.active(record.uuid).filter { it.type.blocksLogin }),
                    )
                record.type == Type.MUTE && player != null -> {
                    mutes.merge(record.uuid, record.expiresAt ?: Long.MAX_VALUE, ::maxOf)
                    player.sendMessage(
                        Component.text(
                            "You have been muted${remaining(record.expiresAt ?: Long.MAX_VALUE)}: ${record.reason}",
                            NamedTextColor.RED,
                        ),
                    )
                }
                record.type == Type.WARN && player != null ->
                    player.sendMessage(Component.text("You have been warned: ${record.reason}", NamedTextColor.RED))
            }
        }
    }

    private fun remaining(expiry: Long): String =
        if (expiry == Long.MAX_VALUE) " permanently" else " for ${formatDuration(Duration.ofMillis(expiry - System.currentTimeMillis()))}"

    /** The ban screen for every active ban of one player; stacked bans list each reason. */
    fun kickMessage(records: List<Record>): Component {
        val types = records.map { it.type }.toSet()
        val title =
            when {
                Type.BLACKLIST in types -> "You are permanently blacklisted from Aechronis"
                Type.ITERATION_BAN in types -> "You are banned from Aechronis for this iteration"
                else -> "You are banned from Aechronis"
            }
        val permanent = records.any { it.expiresAt == null }
        val expires =
            when {
                Type.BLACKLIST in types -> "Never expires"
                Type.ITERATION_BAN in types -> "Until the next iteration"
                permanent -> "Never expires"
                else -> "Expires in ${formatDuration(Duration.ofMillis(records.maxOf { it.expiresAt!! } - System.currentTimeMillis()))}"
            }
        var message = Component.text(title, NamedTextColor.RED).append(Component.newline())
        records.map { it.reason }.distinct().forEach {
            message = message.append(Component.newline()).append(Component.text("Reason: $it", NamedTextColor.WHITE))
        }
        return message
            .append(Component.newline())
            .append(Component.text(expires, NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text(SITE, NamedTextColor.GOLD))
    }

    fun clear() = mutes.clear()
}
