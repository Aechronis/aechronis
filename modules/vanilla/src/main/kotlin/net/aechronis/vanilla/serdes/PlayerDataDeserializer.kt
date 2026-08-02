package net.aechronis.vanilla.serdes

import net.aechronis.vanilla.managers.Commands
import net.aechronis.vanilla.managers.KillShop
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.item.ItemStack
import java.util.UUID

object PlayerDataDeserializer {
    fun deserialize(
        player: Player,
        data: CompoundBinaryTag,
    ) {
        player.health = data.getFloat("Health", 20f)

        player.food = data.getInt("Food", 20)

        player.foodSaturation = data.getFloat("FoodSaturation", 20f)

        player.setTag(KillShop.POINTS_TAG, data.getInt("Points", 0))

        player.gameMode =
            runCatching { GameMode.valueOf(data.getString("GameMode")) }
                .getOrDefault(GameMode.SURVIVAL)
        player.isAllowFlying = data.getBoolean("AllowFlying", false)
        player.isFlying = data.getBoolean("Flying", false) && player.isAllowFlying

        val position = data.getCompound("Position")
        deserializePosition(player, position)

        deserializeInventory(player.inventory, data.getList("Inventory"))
        deserializeInventory(Commands.getEnderChest(player), data.getList("EnderChest"))
        val cursorItem = data.getCompound("CursorItem")
        if (cursorItem.keySet().isNotEmpty()) player.inventory.cursorItem = ItemStack.fromItemNBT(cursorItem)

        val ignoredList = data.getList("Ignored", BinaryTagTypes.STRING)
        if (ignoredList.size() > 0) {
            val set = mutableSetOf<UUID>()
            for (tag in ignoredList) {
                set.add(UUID.fromString((tag as StringBinaryTag).value()))
            }
            Commands.setIgnored(player, set)
        }
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
        target: AbstractInventory,
        entries: ListBinaryTag,
    ) {
        target.clear()
        for (entry in entries) {
            if (entry !is CompoundBinaryTag) continue

            val slot = entry.getByte("Slot", -1)
            if (slot < 0 || slot >= target.size) {
                continue
            }

            val itemBuilder = CompoundBinaryTag.builder()
            for (key in entry.keySet()) {
                if (key != "Slot") {
                    itemBuilder.put(key, entry.get(key)!!)
                }
            }
            val item = ItemStack.fromItemNBT(itemBuilder.build())
            target.setItemStack(slot.toInt(), item)
        }
    }
}
