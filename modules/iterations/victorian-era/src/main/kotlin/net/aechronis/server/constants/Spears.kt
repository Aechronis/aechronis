package net.aechronis.server.constants

import net.aechronis.combat.objects.Throwable
import net.aechronis.combat.objects.ThrowableImpacts
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Spears {
    val spear =
        Throwable(
            name = "spear",
            itemName = Component.text("Spear", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            throwSpeed = 1.4,
            gravity = 0.05,
            throwCooldownMillis = 5_000L,
            onImpact = ThrowableImpacts.damage(24F),
        )

    val all: List<Throwable> = listOf(spear)
}
