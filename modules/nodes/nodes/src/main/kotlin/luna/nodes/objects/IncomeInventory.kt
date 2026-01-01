///**
// * IncomeInventory
// *
// * Inventory container for adding income to town
// */
//
//package luna.nodes.objects
//
//import org.bukkit.Bukkit
//import org.bukkit.Material
//import org.bukkit.inventory.Inventory
//import org.bukkit.inventory.InventoryHolder
//import org.bukkit.inventory.ItemStack
//import java.util.EnumMap
//
//public class IncomeInventory : InventoryHolder {
//
//    // normal items:
//    // map material -> current amount of it in storage
//    val storage: EnumMap<Material, Int> = EnumMap<Material, Int>(Material::class.java)
//
//    // inventory gui object, only populate when open
//    @Suppress("PropertyName")
//    val _inventory: Inventory = Bukkit.createInventory(this, 54, "Town Income")
//
//    // internal, add items to storage
//    @Suppress("FunctionName")
//    private fun _add(mat: Material, amount: Int) {
//        this.storage.get(mat)?.let { current ->
//            storage.put(mat, current + amount)
//        } ?: run {
//            storage.put(mat, amount)
//        }
//    }
//
//    // public interface to add new items to storage
//    public fun add(mat: Material, amount: Int, meta: Int = 0) {
//        if (amount <= 0) {
//            return
//        }
//
//        this._add(mat, amount)
//    }
//
//    // checks if any items in inventory or storage
//    public fun empty(): Boolean = (storage.size == 0)
//
//    // implement getInventory for InventoryHolder
//    public override fun getInventory(): Inventory {
//        // populate inventory
//        while (this.storage.size > 0) {
//            val item = this.storage.iterator().next()
//            val material = item.key
//            val amount = item.value
//            this.storage.remove(material)
//
//            val itemsFailedToAdd = this._inventory.addItem(ItemStack(material, amount))
//            if (itemsFailedToAdd.size > 0) {
//                val leftoverItems = itemsFailedToAdd.get(0)!!
//                this.storage.put(material, leftoverItems.amount)
//                return this._inventory
//            }
//        }
//
//        return this._inventory
//    }
//
//    // moves items into storage and clear inventory
//    // use this before saving game state
//    // (still potential to dupe items if storage cleared and
//    // game crashes before next save finishes)
//    //
//    // By default, only pushes to backend if no players are viewing
//    // the income (so items don't seem like they're disappearing)
//    // "force" option force-pushes items to backend (e.g. on server close)
//    //
//    // return if items moved (needed to determine if town needsUpdate()):
//    // - true: if any items moved
//    // - false: if no items moved
//    public fun pushToStorage(force: Boolean): Boolean {
//        var hasMovedItems = false
//
//        val viewers = this._inventory.viewers
//        if (viewers.size == 0 || force) {
//            for (itemStack in this._inventory.iterator()) {
//                if (itemStack != null) {
//                    this._add(itemStack.type, itemStack.amount)
//
//                    hasMovedItems = true
//                }
//            }
//            this._inventory.clear()
//        }
//
//        return hasMovedItems
//    }
//}
