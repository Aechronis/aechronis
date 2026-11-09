package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Throwable
import net.aechronis.combat.objects.ThrowableImpacts
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Grenades {
    val rgo =
        Throwable(
            name = "rgo-grenade",
            itemName = Component.text("RGO Grenade", NamedTextColor.DARK_GREEN).decoration(TextDecoration.ITALIC, false),
            itemModel = "aechronis:rgo-grenade",
            projectileModel = "aechronis:rgo-grenade",
            throwSpeed = 1.0,
            gravity = 0.05,
            onImpact = ThrowableImpacts.explosion(radius = 2, damage = 35F, ammoType = AmmoTypes.EXPLOSIVE),
        )
}
