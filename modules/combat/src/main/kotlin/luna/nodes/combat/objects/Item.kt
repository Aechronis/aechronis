package luna.nodes.combat.objects

import luna.nodes.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

open class Item(
    val name: String,
    val itemName: Component,
    val itemLore: List<Component> = emptyList(),
    val itemModel: String = "${Tags.namespace}:$name",
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
            for (item in items) {
                registeredItems[item.name] = item
            }
        }

        fun getFromName(name: String): Item? = registeredItems[name]

        fun getFromItemStack(itemStack: ItemStack): Item? = registeredItems[itemStack.getTag(Tags.name)]
    }
}
