package net.aechronis.vanilla.managers

import net.aechronis.vanilla.listeners.MannequinListener
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack

object Mannequin {
    val inventories = mutableMapOf<EntityCreature, Inventory>()
    private val corpses = mutableMapOf<Inventory, EntityCreature>()

    fun newLootInventory(deadName: String): Inventory = Inventory(InventoryType.CHEST_6_ROW, Component.text("$deadName's body"))

    fun register(
        corpse: EntityCreature,
        inventory: Inventory,
    ) {
        inventories[corpse] = inventory
        corpses[inventory] = corpse
        syncArmor(inventory)
    }

    fun unregister(corpse: EntityCreature) {
        val inventory = inventories.remove(corpse) ?: return
        corpses.remove(inventory)
    }

    /** Gives a player the corpse's remaining loot and removes the corpse. */
    fun claim(
        corpse: EntityCreature,
        player: Player,
    ): Boolean {
        val inventory = inventories[corpse] ?: return false

        // Closing viewers can return cursor items or otherwise change the loot inventory.
        // Do it before reading the contents so only the remaining loot is claimed.
        inventory.viewers.toList().forEach { it.closeInventory() }
        for (slot in 0..<inventory.size) {
            val stack = inventory.getItemStack(slot)
            if (!stack.isAir && !player.inventory.addItemStack(stack)) player.dropItem(stack)
        }
        despawn(inventory)
        return true
    }

    fun despawnIfEmpty(inventory: Inventory): Boolean {
        if (inventory !in corpses) return false
        for (slot in 0..<inventory.size) {
            if (!inventory.getItemStack(slot).isAir) return false
        }
        despawn(inventory)
        return true
    }

    fun despawn(inventory: Inventory) {
        val corpse = corpses.remove(inventory) ?: return
        inventories.remove(corpse)
        inventory.viewers.toList().forEach { it.closeInventory() }
        corpse.remove()
    }

    fun syncArmor(inventory: Inventory) {
        val corpse = corpses[inventory] ?: return
        for (equipmentSlot in EquipmentSlot.armors()) {
            val originalArmor = inventory.getItemStack(equipmentSlot.armorSlot())
            val armor =
                originalArmor.takeIf { it.equipmentSlot == equipmentSlot }
                    ?: inventory.itemStacks.firstOrNull { it.equipmentSlot == equipmentSlot }
                    ?: ItemStack.AIR
            corpse.setEquipment(equipmentSlot, armor)
        }
    }

    private val ItemStack.equipmentSlot: EquipmentSlot?
        get() = get(DataComponents.EQUIPPABLE)?.slot()

    fun init() {
        MannequinListener.init()
    }
}
