/**
 * Constants for diplomatic relations
 * War/enemy relationships are between nations only.
 * Alliances are between nations only.
 * Towns inherit diplomatic status from their nation.
 */

package net.aechronis.nodes.constants

import net.aechronis.nodes.utils.ChatColor
import net.kyori.adventure.text.format.NamedTextColor

/**
 * Simple relationship groups:
 * Town - contains town residents
 * Nation - towns in same nation
 * Ally - towns in allied nations
 * Neutral - neutral towns, or players with no town
 * Enemy - towns in enemy nations
 */
enum class DiplomaticRelationship(
    val chatColor: String,
    val textColor: NamedTextColor,
) {
    TOWN(ChatColor.GREEN, NamedTextColor.GREEN),
    NATION(ChatColor.DARK_GREEN, NamedTextColor.DARK_GREEN),
    ALLY(ChatColor.DARK_AQUA, NamedTextColor.DARK_AQUA),
    NEUTRAL(ChatColor.GOLD, NamedTextColor.GOLD),
    ENEMY(ChatColor.RED, NamedTextColor.RED),
}
