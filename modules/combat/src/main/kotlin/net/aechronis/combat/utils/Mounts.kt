package net.aechronis.combat.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import java.time.Duration

/**
 * Living mounts such as horses. Vehicle seats are display entities, so only animals count.
 * Riders may only use guns marked mountable (with extra spread) and melee weapons.
 */
object Mounts {
    const val SPREAD_MULTIPLIER = 2F

    private val blockedTimes = Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(200))

    fun mount(player: Player): LivingEntity? = player.vehicle as? LivingEntity

    fun isMounted(player: Player): Boolean = mount(player) != null

    fun showBlocked(player: Player) {
        player.showTitle(
            Title.title(
                Component.empty(),
                Component.text("You can't use this while riding", NamedTextColor.RED),
                blockedTimes,
            ),
        )
    }
}
