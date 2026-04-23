package net.aechronis.vanilla.playerdata

import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack

object PlayerDataDeserializer {
    fun deserialize(
        player: Player,
        data: CompoundBinaryTag,
    ) {
        player.health = data.getFloat("Health", 20f)

        player.food = data.getInt("Food", 20)

        player.foodSaturation = data.getFloat("FoodSaturation", 20f)

        val position = data.getCompound("Position")
        deserializePosition(player, position)

        val inventory = data.getList("Inventory")
        deserializeInventory(player, inventory)
    }

    private fun deserializePosition(
        player: Player,
        position: CompoundBinaryTag,
    ) {
        if (position.keySet().isEmpty()) return

        player.teleport(
            Pos(
                position.getDouble("X", 0.0),
                position.getDouble("Y", 64.0),
                position.getDouble("Z", 0.0),
                position.getFloat("Yaw", 0f),
                position.getFloat("Pitch", 0f),
            ),
        )
    }

    private fun deserializeInventory(
        player: Player,
        inventory: ListBinaryTag,
    ) {
        for (entry in inventory) {
            if (entry !is CompoundBinaryTag) continue

            val slot = entry.getByte("Slot", -1)
            if (slot < 0 || slot >= player.inventory.size) {
                continue
            }

            val itemBuilder = CompoundBinaryTag.builder()
            for (key in entry.keySet()) {
                if (key != "Slot") {
                    itemBuilder.put(key, entry.get(key)!!)
                }
            }
            val item = ItemStack.fromItemNBT(itemBuilder.build())
            player.inventory.setItemStack(slot.toInt(), item)
        }
    }
}
