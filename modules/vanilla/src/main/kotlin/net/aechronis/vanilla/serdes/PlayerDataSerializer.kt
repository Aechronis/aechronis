package net.aechronis.vanilla.serdes

import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.ItemStack

object PlayerDataSerializer {
    fun serialize(snapshot: PlayerDataSnapshot): CompoundBinaryTag {
        val builder =
            CompoundBinaryTag
                .builder()
                .putFloat("Health", snapshot.health)
                .putInt("Food", snapshot.food)
                .putFloat("FoodSaturation", snapshot.foodSaturation)
                .putInt("Points", snapshot.points)
                .putString("GameMode", snapshot.gameMode.name)
                .putBoolean("AllowFlying", snapshot.allowFlying)
                .putBoolean("Flying", snapshot.flying)
                .put("Position", serializePosition(snapshot.position))
                .put("Inventory", serializeInventory(snapshot.inventory))
                .put("EnderChest", serializeInventory(snapshot.enderChest))

        val cursorItem = snapshot.cursorItem
        if (!cursorItem.isAir) builder.put("CursorItem", cursorItem.toItemNBT())

        val ignored = snapshot.ignored
        if (ignored.isNotEmpty()) {
            val list = ListBinaryTag.builder(BinaryTagTypes.STRING)
            ignored.forEach { list.add(StringBinaryTag.stringBinaryTag(it.toString())) }
            builder.put("Ignored", list.build())
        }

        return builder.build()
    }

    private fun serializePosition(position: Pos): CompoundBinaryTag =
        CompoundBinaryTag
            .builder()
            .putDouble("X", position.x())
            .putDouble("Y", position.y())
            .putDouble("Z", position.z())
            .putFloat("Yaw", position.yaw())
            .putFloat("Pitch", position.pitch())
            .build()

    private fun serializeInventory(inventory: List<ItemStack>): ListBinaryTag {
        val builder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)

        for ((slot, item) in inventory.withIndex()) {
            if (item.isAir()) continue

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
