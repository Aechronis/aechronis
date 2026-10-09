package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.server.modules.ModuleResources
import net.kyori.adventure.text.Component
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

open class Item(
    val name: String,
    val itemName: Component,
    val itemLore: List<Component> = emptyList(),
    val itemModel: String = "${Tags.NAMESPACE}:$name",
    val material: Material = Material.ECHO_SHARD,
) {
    open fun toItemStack(): ItemStack =
        ItemStack
            .of(material)
            .withItemModel(itemModel)
            .withCustomName(itemName)
            .withLore(itemLore)
            .withTag(Tags.name, name)

    companion object {
        val registeredItems: HashMap<String, Item> = hashMapOf()

        fun registerItems(vararg items: Item) {
            require(items.map { it.name }.distinct().size == items.size) { "Duplicate names in item catalogue" }
            require(items.none { it.name in registeredItems }) { "An item name is already registered" }
            for (item in items) {
                registeredItems[item.name] = item
            }
            ModuleResources.own(AutoCloseable { items.forEach { registeredItems.remove(it.name, it) } })
        }

        fun getFromName(name: String): Item? = registeredItems[name]

        fun getFromItemStack(itemStack: ItemStack): Item? = registeredItems[itemStack.getTag(Tags.name)]
    }
}
