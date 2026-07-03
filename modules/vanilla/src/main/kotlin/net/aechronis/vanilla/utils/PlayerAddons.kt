package net.aechronis.vanilla.utils

import net.luckperms.api.LuckPermsProvider
import net.minestom.server.entity.Player

object PlayerAddons {
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
                ?: false
        } catch (_: Exception) {
            false
        }
}
