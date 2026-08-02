package net.aechronis.vanilla.managers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Commands {
    const val MIRRORED_SLOTS = 41
    val lastLocation = HashMap<UUID, Pos>()
    val playerLastSender = HashMap<Player, Player>()
    val ignored = HashMap<UUID, MutableSet<UUID>>()
    private val viewing = ConcurrentHashMap<Inventory, Player>()
    private val viewingLock = Any()
    private val synchronizingInventories =
        Collections.newSetFromMap(IdentityHashMap<AbstractInventory, Boolean>())
    private val enderChests = ConcurrentHashMap<UUID, Inventory>()
    private val closingEnderChests = ConcurrentHashMap.newKeySet<UUID>()

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
        synchronized(viewingLock) {
            viewing[inv] = target
            withoutSynchronization(inv) {
                for (slot in 0 until MIRRORED_SLOTS) {
                    inv.setItemStack(slot, target.inventory.getItemStack(slot))
                }
            }
        }
        if (!viewer.openInventory(inv)) {
            removeView(inv)
        }
    }

    internal fun synchronizeInventoryChange(
        inventory: AbstractInventory,
        slot: Int,
        item: ItemStack,
    ) {
        if (slot !in 0 until MIRRORED_SLOTS) return

        synchronized(viewingLock) {
            if (inventory in synchronizingInventories) return

            val sourceView = inventory as? Inventory
            val target = sourceView?.let(viewing::get)
            if (target != null) {
                setWithoutSynchronization(target.inventory, slot, item)
                for ((view, viewedPlayer) in viewing) {
                    if (view !== sourceView && viewedPlayer === target) {
                        setWithoutSynchronization(view, slot, item)
                    }
                }
                return
            }

            for ((view, viewedPlayer) in viewing) {
                if (viewedPlayer.inventory === inventory) {
                    setWithoutSynchronization(view, slot, item)
                }
            }
        }
    }

    internal fun removeView(inventory: Inventory): Boolean =
        synchronized(viewingLock) {
            viewing.remove(inventory) != null
        }

    private fun setWithoutSynchronization(
        inventory: AbstractInventory,
        slot: Int,
        item: ItemStack,
    ) = withoutSynchronization(inventory) {
        inventory.setItemStack(slot, item)
    }

    private inline fun withoutSynchronization(
        inventory: AbstractInventory,
        action: () -> Unit,
    ) {
        synchronizingInventories.add(inventory)
        try {
            action()
        } finally {
            synchronizingInventories.remove(inventory)
        }
    }

    fun getEnderChest(player: Player): Inventory =
        enderChests.computeIfAbsent(player.uuid) {
            Inventory(InventoryType.CHEST_3_ROW, Component.text("${player.username}'s Ender Chest"))
        }

    fun openEnderChest(
        viewer: Player,
        target: Player,
    ) {
        if (target.uuid in closingEnderChests) return
        viewer.openInventory(getEnderChest(target))
    }

    fun closeViewsOf(player: Player) {
        closingEnderChests.add(player.uuid)
        val inventories = synchronized(viewingLock) { viewing.filterValues { it === player }.keys.toList() }
        for (inventory in inventories) {
            inventory.viewers.toList().forEach { it.closeInventory() }
            removeView(inventory)
        }

        enderChests[player.uuid]?.viewers?.toList()?.forEach { it.closeInventory() }
    }

    fun removeEnderChest(player: Player) {
        enderChests.remove(player.uuid)
    }

    fun allowEnderChest(player: Player) {
        closingEnderChests.remove(player.uuid)
    }
}
