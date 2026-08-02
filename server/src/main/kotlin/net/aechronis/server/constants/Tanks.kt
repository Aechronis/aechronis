package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.Tank
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec

object Tanks {
    private val m1a1AbramsHitbox =
        Hitbox(
            listOf(
                HitboxPart(
                    offset = Vec(0.0, 0.0, 0.0),
                    size = Vec(1.6, 1.5, 2.6),
                ),
            ),
        )

    val m1a1Abrams =
        Tank(
            name = "m1a1-abrams",
            itemName = Component.text("M1A1 Abrams", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            model = "aechronis:m1a1-abrams",
            hitbox = m1a1AbramsHitbox,
            scale = 3.0,
            health =
                Health(
                    1500f,
                    mapOf(
                        AmmoTypes.NORMAL to 2.5f,
                        AmmoTypes.EXPLOSIVE to 750f,
                        AmmoTypes.BOMB to 500f,
                        AmmoTypes.MISSILE to 750f,
                    ),
                ),
            projectileModel = "aechronis:shell",
        )
}
