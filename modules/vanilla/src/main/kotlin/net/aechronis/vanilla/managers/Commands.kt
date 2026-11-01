package net.aechronis.vanilla.managers

import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Commands {
    private val enderChests = ConcurrentHashMap<UUID, Inventory>()
    private val closingEnderChests = ConcurrentHashMap.newKeySet<UUID>()

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

    fun closeEnderChest(player: Player) {
        closingEnderChests.add(player.uuid)
        enderChests[player.uuid]?.viewers?.toList()?.forEach { it.closeInventory() }
    }

    fun removeEnderChest(player: Player) {
        enderChests.remove(player.uuid)
    }

    fun allowEnderChest(player: Player) {
        closingEnderChests.remove(player.uuid)
    }

    fun shutdown() {
        enderChests.values.forEach { inventory -> inventory.viewers.toList().forEach { it.closeInventory() } }
        enderChests.clear()
        closingEnderChests.clear()
    }
}
