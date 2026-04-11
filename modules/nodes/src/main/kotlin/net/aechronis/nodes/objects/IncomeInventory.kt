/**
 * IncomeInventory
 *
 * Inventory container for adding income to town
 */

package net.aechronis.nodes.objects

import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class IncomeInventory {

    // normal items:
    // map material -> current amount of it in storage
    val storage: MutableMap<Material, Int> = mutableMapOf()

    // inventory gui object, only populate when open
    @Suppress("PropertyName")
    val _inventory: Inventory = Inventory(InventoryType.CHEST_5_ROW, "Town Income")

    // internal, add items to storage
    @Suppress("FunctionName")
    private fun _add(mat: Material, amount: Int) {
        this.storage[mat]?.let { current ->
            storage.put(mat, current + amount)
        } ?: run {
            storage.put(mat, amount)
        }
    }

    // public interface to add new items to storage
    fun add(mat: Material, amount: Int) {
        if (amount <= 0) {
            return
        }

        this._add(mat, amount)
    }

    // checks if any items in inventory or storage
    fun empty(): Boolean = (storage.isEmpty())

    // get inventory for viewing
    fun getInventory(): Inventory {
        // populate inventory
        while (this.storage.isNotEmpty()) {
            val item = this.storage.iterator().next()
            val material = item.key
            val amount = item.value
            this.storage.remove(material)

            // find empty slots and add items
            val maxStackSize = material.maxStackSize()
            var remainingAmount = amount

            for (slot in 0 until _inventory.size) {
                if (remainingAmount <= 0) break

                val existingItem = _inventory.getItemStack(slot)

                // check if slot is empty
                if (existingItem.isAir) {
                    val stackAmount = minOf(remainingAmount, maxStackSize)
                    _inventory.setItemStack(slot, ItemStack.of(material, stackAmount))
                    remainingAmount -= stackAmount
                }
                // check if slot has same material and can stack more
                else if (existingItem.material() == material && existingItem.amount() < maxStackSize) {
                    val canAdd = maxStackSize - existingItem.amount()
                    val toAdd = minOf(remainingAmount, canAdd)
                    _inventory.setItemStack(slot, existingItem.withAmount(existingItem.amount() + toAdd))
                    remainingAmount -= toAdd
                }
            }

            // if items couldn't fit, put back in storage
            if (remainingAmount > 0) {
                this.storage.put(material, remainingAmount)
                return this._inventory
            }
        }

        return this._inventory
    }

    // moves items into storage and clear inventory
    // use this before saving game state
    // (still potential to dupe items if storage cleared and
    // game crashes before next save finishes)
    //
    // By default, only pushes to backend if no players are viewing
    // the income (so items don't seem like they're disappearing)
    // "force" option force-pushes items to backend (e.g. on server close)
    //
    // return if items moved (needed to determine if town needsUpdate()):
    // - true: if any items moved
    // - false: if no items moved
    fun pushToStorage(force: Boolean): Boolean {
        var hasMovedItems = false

        val viewers = this._inventory.viewers
        if (viewers.isEmpty() || force) {
            for (slot in 0 until _inventory.size) {
                val itemStack = _inventory.getItemStack(slot)
                if (!itemStack.isAir) {
                    this._add(itemStack.material(), itemStack.amount())
                    hasMovedItems = true
                }
            }
            this._inventory.clear()
        }

        return hasMovedItems
    }
}
