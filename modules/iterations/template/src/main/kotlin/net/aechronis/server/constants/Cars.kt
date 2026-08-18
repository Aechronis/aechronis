package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec

object Cars {
    // The model is 16 pixels wide/deep and is displayed at scale 3.0.
    private val humveeHitbox =
        Hitbox(
            listOf(
                HitboxPart(
                    offset = Vec(0.0, -0.24, 0.0),
                    size = Vec(1.6, 1.2, 1.4),
                ),
            ),
        )

    val humvee =
        Car(
            name = "humvee",
            itemName = Component.text("Humvee", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            hitbox = humveeHitbox,
            health =
                Health(
                    500F,
                    mapOf(
                        AmmoTypes.NORMAL to 1f,
                        AmmoTypes.MISSILE to 1f,
                        AmmoTypes.BOMB to 1f,
                        AmmoTypes.EXPLOSIVE to 1f,
                    ),
                ),
            placeTime = 2000,
            scale = 3.0,
            maxSpeed = 1.25f,
            turnSpeed = 10f,
            maxClimbHeight = 1.5f,
            seatOffsets =
                listOf(
                    Vec.ZERO,
                    Vec(1.0, 0.0, 0.0),
                ),
        )
}
