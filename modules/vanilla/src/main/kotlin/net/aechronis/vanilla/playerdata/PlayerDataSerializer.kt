package net.aechronis.vanilla.playerdata

import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.inventory.PlayerInventory

object PlayerDataSerializer {
    fun serialize(player: Player): CompoundBinaryTag =
        CompoundBinaryTag
            .builder()
            .putFloat("Health", player.getHealth())
            .putInt("Food", player.food)
            .putFloat("FoodSaturation", player.foodSaturation)
            .put("Position", serializePosition(player.position))
            .put("Inventory", serializeInventory(player.inventory))
            .build()

    private fun serializePosition(position: Pos): CompoundBinaryTag =
        CompoundBinaryTag
            .builder()
            .putDouble("X", position.x())
            .putDouble("Y", position.y())
            .putDouble("Z", position.z())
            .putFloat("Yaw", position.yaw())
            .putFloat("Pitch", position.pitch())
            .build()

    private fun serializeInventory(inventory: PlayerInventory): ListBinaryTag {
        val builder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND)

        for (slot in 0..<inventory.getSize()) {
            val item = inventory.getItemStack(slot)
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
