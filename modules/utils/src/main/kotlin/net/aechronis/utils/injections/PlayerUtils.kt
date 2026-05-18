package net.aechronis.utils.injections

import net.aechronis.utils.helpers.Message
import net.luckperms.api.LuckPermsProvider
import net.minestom.server.entity.Player
import net.minestom.server.inventory.TransactionOption
import net.minestom.server.item.ItemStack

object PlayerUtils {
    fun Player.giveDrops(drops: List<ItemStack>): Boolean {
        val toAdd = drops.filter { !it.isAir && it.amount() > 0 }
        if (toAdd.isEmpty()) return true

        val added = mutableListOf<ItemStack>()
        for (stack in toAdd) {
            if (this.inventory.addItemStack(stack)) {
                added.add(stack)
            } else {
                added.forEach { this.inventory.takeItemStack(it, TransactionOption.ALL_OR_NOTHING) }
                Message.errorNotifcation(this, "Your inventory is full!")
                return false
            }
        }
        return true
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
