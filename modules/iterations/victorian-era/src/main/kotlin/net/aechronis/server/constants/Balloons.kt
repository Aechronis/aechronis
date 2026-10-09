package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.server.objects.Balloon
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec

object Balloons {
    // Placeholder model is 16 pixels tall at scale 6.0: envelope above, basket below, origin at the model centre.
    private val hotAirBalloonHitbox =
        Hitbox(
            listOf(
                HitboxPart(offset = Vec(0.0, 1.5, 0.0), size = Vec(2.2, 1.5, 2.2)),
                HitboxPart(offset = Vec(0.0, -2.25, 0.0), size = Vec(1.1, 0.75, 1.1)),
            ),
        )

    val hotAirBalloon =
        Balloon(
            name = "hot-air-balloon",
            itemName = Component.text("Hot Air Balloon", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            scale = 6.0,
            hitbox = hotAirBalloonHitbox,
            // 20 rifle shots: a rifle hit costs 47 (average rifle damage)
            health =
                Health(
                    940F,
                    mapOf(
                        AmmoTypes.NORMAL to 47F,
                        AmmoTypes.EXPLOSIVE to 470F,
                        AmmoTypes.BOMB to 940F,
                        AmmoTypes.MISSILE to 940F,
                    ),
                ),
            // holds 10 coal, 45 seconds each
            maxFuel = 9_000,
            // driver + 3 passengers in the basket corners
            seatOffsets =
                listOf(
                    Vec(0.6, -2.7, 0.6),
                    Vec(-0.6, -2.7, 0.6),
                    Vec(0.6, -2.7, -0.6),
                    Vec(-0.6, -2.7, -0.6),
                ),
        )
}
