package net.aechronis.combat.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player

/**
 * Living mounts such as horses. Vehicle seats are display entities, so only animals count.
 * Riders may only use guns marked mountable and melee weapons.
 */
object Mounts {
    fun mount(player: Player): LivingEntity? = player.vehicle as? LivingEntity

    fun isMounted(player: Player): Boolean = mount(player) != null

    // The same cross as reloading without ammo.
    fun showBlocked(player: Player) {
        player.showTitle(
            Title.title(
                Component.empty(),
                Component.text("✕").color(TextColor.color(0.5F, 0F, 0F)).shadowColor(ShadowColor.none()),
                0,
                10,
                10,
            ),
        )
    }
}
