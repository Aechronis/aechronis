package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Grenade
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Grenades {
    val castIronBall =
        Grenade(
            name = "cast-iron-ball-grenade",
            itemName = Component.text("Cast-Iron Ball Grenade", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "aechronis:cast-iron-ball-grenade",
            projectileModel = "aechronis:cast-iron-ball-grenade",
            fuseTimeMillis = 5_000L,
            throwSpeed = 1.0,
            gravity = 0.05,
            explosionRadius = 2,
            explosionFire = 0.0,
            explosionDamage = 35F,
            ammoType = AmmoTypes.EXPLOSIVE,
        )

    val all: List<Grenade> = listOf(castIronBall)
}
