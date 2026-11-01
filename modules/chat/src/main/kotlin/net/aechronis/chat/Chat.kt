package net.aechronis.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import java.util.UUID

object Chat {
    private val playerLastSender = HashMap<Player, Player>()
    private lateinit var ignored: IgnoreStore

    fun init(store: IgnoreStore) {
        ignored = store
    }

    fun toggleIgnored(
        player: Player,
        targets: List<UUID>,
    ): Int = ignored.toggle(player.uuid, targets)

    fun isBlocked(
        a: Player,
        b: Player,
    ): Boolean = ignored.isBlocked(a.uuid, b.uuid)

    internal fun getLastSender(player: Player): Player? = synchronized(playerLastSender) { playerLastSender[player] }

    internal fun removeLastSenderReferences(player: Player) {
        synchronized(playerLastSender) {
            playerLastSender.entries.removeIf { (sender, receiver) -> sender === player || receiver === player }
        }
    }

    fun sendMessage(
        sender: Player,
        receiver: Player?,
        message: String,
    ) {
        if (receiver == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
            return
        }

        if (isBlocked(sender, receiver)) {
            sender.sendMessage(Component.text("You can't message this player.", NamedTextColor.RED))
            return
        }

        sender.sendMessage(
            Component.text("You Whispered to ${receiver.username}: $message").color(NamedTextColor.LIGHT_PURPLE),
        )

        receiver.sendMessage(
            Component.text("${sender.username} Whispered: $message").color(NamedTextColor.LIGHT_PURPLE),
        )

        synchronized(playerLastSender) {
            playerLastSender[receiver] = sender
            playerLastSender[sender] = receiver
        }
    }

    fun shutdown() {
        synchronized(playerLastSender) { playerLastSender.clear() }
    }
}
