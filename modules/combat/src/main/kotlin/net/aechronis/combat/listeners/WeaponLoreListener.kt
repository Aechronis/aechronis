package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.minestom.server.component.DataComponents
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.item.ItemStack

object WeaponLoreListener {
    internal fun refreshLore(itemStack: ItemStack): ItemStack {
        val gun = Item.getFromItemStack(itemStack) as? Gun ?: return itemStack
        if (itemStack.get(DataComponents.LORE) == gun.itemLore) return itemStack
        return itemStack.withLore(gun.itemLore)
    }

    internal fun onInventoryItemChange(event: InventoryItemChangeEvent) {
        val refreshed = refreshLore(event.newItem)
        if (refreshed === event.newItem) return
        event.inventory.setItemStack(event.slot, refreshed)
    }

    fun init() {
        Combat.lowPriorityEventNode.addListener(InventoryItemChangeEvent::class.java, ::onInventoryItemChange)
    }
}
