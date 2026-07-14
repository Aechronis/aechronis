package net.aechronis.vanilla.utils

import net.aechronis.utils.Command
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Combat
import net.minestom.server.entity.Player

open class VanillaCommand(
    name: String,
    permission: String? = null,
    vararg aliases: String,
) : Command(name, permission, *aliases) {
    override fun canExecute(player: Player): Boolean {
        if (Combat.isInCombat(player) && name !in Vanilla.config.combatAllowedCommands) {
            Message.error(player, "You can't use that command while in combat")
            return false
        }

        return true
    }
}
