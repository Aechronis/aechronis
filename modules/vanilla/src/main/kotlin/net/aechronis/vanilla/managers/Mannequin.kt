package net.aechronis.vanilla.managers

import net.aechronis.vanilla.listeners.MannequinDamageListener
import net.aechronis.vanilla.listeners.MannequinPlayerListener
import net.kyori.adventure.text.Component
import net.minestom.server.entity.EntityCreature
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType

object Mannequin {
    val inventories = mutableMapOf<EntityCreature, Inventory>()

    fun newLootInventory(deadName: String): Inventory =
        Inventory(InventoryType.CHEST_6_ROW, Component.text("$deadName's body"))

    fun init() {
        MannequinDamageListener.init()
        MannequinPlayerListener.init()
    }
}
