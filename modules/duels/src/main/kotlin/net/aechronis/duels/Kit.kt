package net.aechronis.duels

import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack

/** Inventory slots 0-35 in [items]; armor is separate so it lands in the right equipment slot. */
class KitContents(
    val items: Map<Int, ItemStack>,
    val helmet: ItemStack = ItemStack.AIR,
    val chestplate: ItemStack = ItemStack.AIR,
    val leggings: ItemStack = ItemStack.AIR,
    val boots: ItemStack = ItemStack.AIR,
)

class Kit(
    val id: String,
    val displayName: String,
    val icon: () -> ItemStack,
    val contents: () -> KitContents,
) {
    fun apply(player: Player) {
        val c = contents()
        player.inventory.clear()
        c.items.forEach { (slot, item) -> player.inventory.setItemStack(slot, item) }
        player.helmet = c.helmet
        player.chestplate = c.chestplate
        player.leggings = c.leggings
        player.boots = c.boots
    }
}

/** Iteration modules register kits during configure. */
object Kits {
    private val kits = LinkedHashMap<String, Kit>()

    fun all(): List<Kit> = synchronized(kits) { kits.values.toList() }

    fun get(id: String): Kit? = synchronized(kits) { kits[id] }

    fun register(vararg toAdd: Kit) {
        synchronized(kits) {
            require(toAdd.none { it.id in kits }) { "A duel kit id is already registered" }
            toAdd.forEach { kits[it.id] = it }
        }
    }
}
