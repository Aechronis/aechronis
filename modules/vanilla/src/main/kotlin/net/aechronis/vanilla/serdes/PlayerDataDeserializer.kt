package net.aechronis.vanilla.serdes

import net.aechronis.vanilla.managers.Bundles
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
        val cursorItem = data.getCompound("CursorItem")
        if (cursorItem.keySet().isNotEmpty()) {
            runCatching { ItemStack.fromItemNBT(cursorItem) }
                .getOrNull()
                ?.takeIf(Bundles::isSafeForTransport)
                ?.let { player.inventory.cursorItem = it }
        }

        deserializeModuleState(player, data)
    }

    /** Restores data owned by this module without changing the live Minestom player state. */
    fun deserializeModuleState(
        player: Player,
        data: CompoundBinaryTag,
    ) {
        deserializeInventory(Commands.getEnderChest(player), data.getList("EnderChest"))

        val ignored = mutableSetOf<UUID>()
        for (tag in data.getList("Ignored", BinaryTagTypes.STRING)) {
            runCatching { UUID.fromString((tag as StringBinaryTag).value()) }
                .getOrNull()
                ?.let(ignored::add)
        }
        Commands.setIgnored(player, ignored)
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
            val item = runCatching { ItemStack.fromItemNBT(itemBuilder.build()) }.getOrNull() ?: continue
            if (!Bundles.isSafeForTransport(item)) continue
            target.setItemStack(slot.toInt(), item)
        }
    }
}
