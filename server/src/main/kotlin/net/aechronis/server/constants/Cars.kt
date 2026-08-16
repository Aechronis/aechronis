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
    private val truckHitbox =
        Hitbox(
            listOf(
                HitboxPart(
                    offset = Vec(0.4, 0.0, -1.0),
                    size = Vec(1.4, 1.0, 3.0),
                ),
            ),
        )

    val truck =
        Car(
            name = "truck",
            itemName = Component.text("Truck", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            hitbox = truckHitbox,
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
            maxSpeed = 5f,
            turnSpeed = 20f,
            maxClimbHeight = 1.5f,
            seatOffsets =
                listOf(
                    Vec.ZERO,
                    Vec(1.0, 0.0, 0.0),
                ),
        )
}
