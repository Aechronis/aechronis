/**
 * IncomeInventory
 *
 * Inventory container for withdrawing town income.
 */

package net.aechronis.nodes.objects

import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class IncomeInventory {
    // normal items:
    // map material -> current amount of it in storage
    val storage: MutableMap<Material, Int> = mutableMapOf()

    @Suppress("PropertyName")
    val _inventory: Inventory = Inventory(InventoryType.CHEST_5_ROW, "Town Income")

    private var materialized = false
    private var updatingInventory = false
    private var visibleSnapshot: Map<Material, Int> = emptyMap()

    fun add(
        material: Material,
        amount: Int,
    ) {
        if (amount <= 0) return
        storage[material] = (storage[material] ?: 0) + amount
        if (materialized) refillVisibleItems()
    }

    fun empty(): Boolean {
        synchronizeFromInventory()
        return storage.isEmpty()
    }

    fun getInventory(): Inventory {
        if (!materialized) {
            materialized = true
            visibleSnapshot = inventoryCounts()
        } else {
            synchronizeFromInventory()
        }
        refillVisibleItems()
        return _inventory
    }

    fun owns(inventory: AbstractInventory): Boolean = inventory === _inventory

    fun synchronizeFromInventory(): Boolean {
        if (!materialized || updatingInventory) return false
        val current = inventoryCounts()
        if (current == visibleSnapshot) return false

        (current.keys + visibleSnapshot.keys).forEach { material ->
            val delta = (current[material] ?: 0) - (visibleSnapshot[material] ?: 0)
            if (delta == 0) return@forEach
            val updated = (storage[material] ?: 0) + delta
            if (updated > 0) storage[material] = updated else storage.remove(material)
        }
        visibleSnapshot = current
        return true
    }

    fun snapshot(): Map<Material, Int> {
        synchronizeFromInventory()
        return storage.toMap()
    }

    fun pushToStorage(@Suppress("UNUSED_PARAMETER") force: Boolean): Boolean = synchronizeFromInventory()

    private fun refillVisibleItems() {
        val visible = inventoryCounts().toMutableMap()
        updatingInventory = true
        try {
            storage.forEach { (material, total) ->
                var remaining = total - (visible[material] ?: 0)
                if (remaining <= 0) return@forEach

                for (slot in 0 until _inventory.size) {
                    if (remaining <= 0) break
                    val existing = _inventory.getItemStack(slot)
                    val maxStackSize = material.maxStackSize()
                    when {
                        existing.isAir -> {
                            val added = minOf(remaining, maxStackSize)
                            _inventory.setItemStack(slot, ItemStack.of(material, added))
                            remaining -= added
                        }

                        existing.material() == material && existing.amount() < maxStackSize -> {
                            val added = minOf(remaining, maxStackSize - existing.amount())
                            _inventory.setItemStack(slot, existing.withAmount(existing.amount() + added))
                            remaining -= added
                        }
                    }
                }
            }
        } finally {
            visibleSnapshot = inventoryCounts()
            updatingInventory = false
        }
    }

    private fun inventoryCounts(): Map<Material, Int> {
        val counts = mutableMapOf<Material, Int>()
        for (slot in 0 until _inventory.size) {
            val stack = _inventory.getItemStack(slot)
            if (!stack.isAir) counts[stack.material()] = (counts[stack.material()] ?: 0) + stack.amount()
        }
        return counts
    }
}
