package net.aechronis.punishments

import net.aechronis.server.hasPermission
import net.aechronis.server.modules.ModulePermissions
import java.util.UUID

/**
 * LuckPerms nodes, one per staff tier. Rank groups inherit the tier below, but each check also accepts the higher
 * node that implies it, so a single node grant still works.
 */
object PunishPerms {
    /** Jr. Mod: `/punish` with only the Chat category. */
    const val CHAT = "punishments.punish.chat"

    /** Jr. Mod: `/history`. */
    const val HISTORY = "punishments.history"

    /** Mod: every category except Admin+, and the Incomplete flag. */
    const val PUNISH = "punishments.punish"

    /** Sr. Mod: `/addstrike` and `/removestrike`. */
    const val STRIKES = "punishments.strikes"

    /** Sr. Mod: `/alts`. */
    const val ALTS = "punishments.alts"

    /** Admin: the Admin+ (blacklist) category and blacklisted groups. */
    const val BLACKLIST = "punishments.blacklist"

    /** Admin: `/newiter`. */
    const val ADMIN = "punishments.admin"

    val all = listOf(CHAT, HISTORY, PUNISH, STRIKES, ALTS, BLACKLIST, ADMIN)

    fun register() = ModulePermissions.register(*all.toTypedArray())

    fun any(
        uuid: UUID?,
        nodes: Collection<String>,
    ): Boolean = uuid == null || nodes.any { uuid.hasPermission(it) }

    /** Whether [uuid] may punish in [category]; null is the console. */
    fun allowed(
        uuid: UUID?,
        category: Category,
    ): Boolean = any(uuid, category.permissions)

    fun canOpenMenu(uuid: UUID?): Boolean = any(uuid, listOf(CHAT, PUNISH, BLACKLIST))
}
