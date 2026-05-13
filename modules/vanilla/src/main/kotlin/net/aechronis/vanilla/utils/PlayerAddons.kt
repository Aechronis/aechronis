package net.aechronis.vanilla.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.luckperms.api.LuckPermsProvider
import net.minestom.server.advancements.FrameType
import net.minestom.server.advancements.Notification
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object PlayerAddons {
    fun Player.giveDrops(drops: List<ItemStack>) {
        for (stack in drops) {
            if (stack.isAir || stack.amount() <= 0) continue
            if (!this.inventory.addItemStack(stack)) {
                this.sendNotification(
                    Notification(
                        Component.text("Your inventory is full!").color(NamedTextColor.RED),
                        FrameType.TASK,
                        Material.BARRIER,
                    ),
                )
            }
        }
    }

    fun Player.hasPermission(permission: String): Boolean =
        try {
            LuckPermsProvider
                .get()
                .userManager
                .getUser(this.uuid)
                ?.cachedData
                ?.permissionData
                ?.checkPermission(permission)
                ?.asBoolean()
                ?: true
        } catch (_: Exception) {
            false
        }
}
