package net.aechronis.vanilla.managers

import net.aechronis.vanilla.listeners.MannequinListener
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EquipmentSlot
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
