package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Throwable
import net.aechronis.combat.objects.ThrowableImpacts
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Grenades {
    val castIronBall =
        Throwable(
            name = "cast-iron-ball-grenade",
            itemName = Component.text("Cast-Iron Ball Grenade", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "aechronis:cast-iron-ball-grenade",
            projectileModel = "aechronis:cast-iron-ball-grenade",
            throwSpeed = 1.0,
            gravity = 0.05,
            onImpact = ThrowableImpacts.explosion(radius = 2, damage = 35F, ammoType = AmmoTypes.EXPLOSIVE),
        )

    val all: List<Throwable> = listOf(castIronBall)
}
