package net.aechronis.vanilla.utils

import net.luckperms.api.LuckPermsProvider
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack

object PlayerAddons {
    fun Player.giveDrops(drops: List<ItemStack>): Boolean {
        var status = false
        for (stack in drops) {
            if (stack.isAir || stack.amount() <= 0) continue
            if (!this.inventory.addItemStack(stack)) {
                this.sendNotification(
                    Notifications.fullInv,
                )
                status = false
            } else {
                status = true
            }
        }
        return status
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
