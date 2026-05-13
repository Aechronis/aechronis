package net.aechronis.vanilla.serdes

import net.aechronis.vanilla.objects.StorageContents
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.item.ItemStack

object StorageDeserializer {
    fun deserialize(data: CompoundBinaryTag): StorageContents {
        val contents = StorageContents()
        val items = data.getList(StorageSerializer.ITEMS_KEY)
        applyItems(contents, items)
        return contents
    }

    fun applyItems(
        contents: StorageContents,
        items: ListBinaryTag,
    ) {
        val inventory = contents.inventory
        for (entry in items) {
            if (entry !is CompoundBinaryTag) continue

            val slot = entry.getByte("Slot", -1)
            if (slot < 0 || slot >= inventory.size) continue

            val itemBuilder = CompoundBinaryTag.builder()
            for (key in entry.keySet()) {
                if (key != "Slot") {
                    itemBuilder.put(key, entry.get(key)!!)
                }
            }
            val item = ItemStack.fromItemNBT(itemBuilder.build())
            inventory.setItemStack(slot.toInt(), item)
        }
    }
}
