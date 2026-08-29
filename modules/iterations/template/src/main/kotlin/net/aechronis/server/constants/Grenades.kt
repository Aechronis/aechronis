package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Grenade
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Grenades {
    val frag =
        Grenade(
            name = "grenade",
            itemName = Component.text("Frag Grenade", NamedTextColor.DARK_GREEN).decoration(TextDecoration.ITALIC, false),
            itemModel = "aechronis:grenade",
            projectileModel = "aechronis:grenade",
            fuseTimeMillis = 5_000L,
            throwSpeed = 1.0,
            gravity = 0.05,
            explosionRadius = 4,
            explosionFire = 0.0,
            explosionDamage = 30F,
            ammoType = AmmoTypes.EXPLOSIVE,
        )
}
