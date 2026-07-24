package net.aechronis.vanilla.managers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import java.util.UUID

object Commands {
    const val MIRRORED_SLOTS = 41
    val lastLocation = HashMap<UUID, Pos>()
    val viewing = HashMap<Inventory, Player>()
    val playerLastSender = HashMap<Player, Player>()
    val ignored = HashMap<UUID, MutableSet<UUID>>()

    fun getIgnored(player: Player): MutableSet<UUID> = ignored.getOrPut(player.uuid) { mutableSetOf() }

    fun setIgnored(
        player: Player,
        uuids: Set<UUID>,
    ) {
        ignored[player.uuid] = uuids.toMutableSet()
    }

    fun isBlocked(
        a: Player,
        b: Player,
    ): Boolean =
        ignored[a.uuid]?.contains(b.uuid) == true ||
            ignored[b.uuid]?.contains(a.uuid) == true

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

    fun saveLastLocation(player: Player) {
        lastLocation[player.uuid] = player.position
    }

    fun getLastLocation(player: Player): Pos? = lastLocation[player.uuid]

    fun open(
        viewer: Player,
        target: Player,
    ) {
        val inv = Inventory(InventoryType.CHEST_6_ROW, Component.text("${target.username}'s inventory"))
        for (slot in 0 until MIRRORED_SLOTS) {
            inv.setItemStack(slot, target.inventory.getItemStack(slot))
        }
        viewing[inv] = target
        viewer.openInventory(inv)
    }
}
