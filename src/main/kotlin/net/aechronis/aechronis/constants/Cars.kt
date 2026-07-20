package net.aechronis.aechronis.constants

import net.aechronis.combat.objects.Car
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
            health = 500F,
            placeTime = 2000,
            scale = 3.0,
            maxClimbHeight = 1.5f,
            seatOffsets =
                listOf(
                    Vec.ZERO,
                    Vec(1.0, 0.0, 0.0),
                ),
        )
}
