package net.aechronis.vanilla.serdes

import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.inventory.Inventory

object StorageSerializer {
    const val ITEMS_KEY = "Items"

    fun serialize(inventory: Inventory): CompoundBinaryTag =
        CompoundBinaryTag
            .builder()
            .put(ITEMS_KEY, serializeItems(inventory))
            .build()

    private fun serializeItems(inventory: Inventory): ListBinaryTag {
        val builder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)

        for (slot in 0..<inventory.size) {
            val item = inventory.getItemStack(slot)
            if (item.isAir) continue

            val itemNbt = item.toItemNBT()
            val entryBuilder =
                CompoundBinaryTag
                    .builder()
                    .putByte("Slot", slot.toByte())
            for (key in itemNbt.keySet()) {
                entryBuilder.put(key, itemNbt.get(key)!!)
            }
            builder.add(entryBuilder.build())
        }

        return builder.build()
    }
}
