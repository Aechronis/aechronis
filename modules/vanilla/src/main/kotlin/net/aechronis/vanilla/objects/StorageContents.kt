package net.aechronis.vanilla.objects

import net.kyori.adventure.text.Component
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType

class StorageContents(
    val inventory: Inventory = Inventory(InventoryType.CHEST_6_ROW, TITLE),
) {
    fun isEmpty(): Boolean {
        for (slot in 0..<inventory.size) {
            if (!inventory.getItemStack(slot).isAir) return false
        }
        return true
    }

    companion object {
        val TITLE: Component = Component.translatable("container.barrel")
    }
}
