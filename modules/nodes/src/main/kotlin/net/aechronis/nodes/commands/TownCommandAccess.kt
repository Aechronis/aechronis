package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.minestom.server.entity.Player

internal fun isTownStaff(resident: Resident, town: Town): Boolean = resident === town.leader || resident in town.officers

internal fun requireTownLeader(player: Player, resident: Resident, town: Town): Boolean = requireTownAccess(player, resident === town.leader, "You are not the town leader")

internal fun requireTownStaff(player: Player, resident: Resident, town: Town, message: String): Boolean = requireTownAccess(player, isTownStaff(resident, town), message)

internal fun requireTownMember(player: Player, resident: Resident, town: Town): Boolean = requireTownAccess(player, resident.town === town, "Player is not in this town")

private fun requireTownAccess(player: Player, allowed: Boolean, message: String): Boolean {
    if (!allowed) Message.error(player, message)
    return allowed
}
