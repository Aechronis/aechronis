package net.aechronis.aechronis.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Boat
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec

object Ships {
    val testBoat =
        Boat(
            name = "test-ship",
            itemName = Component.text("Test Ship", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            model = "aechronis:boat",
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(
                            offset = Vec(0.0, 1.0, 0.0),
                            size = Vec(1.0, 1.0, 1.0),
                        ),
                    ),
                ),
            scale = 2.5,
            health =
                Health(
                    100f,
                    mapOf(
                        AmmoTypes.NORMAL to 1f,
                        AmmoTypes.EXPLOSIVE to 25f,
                        AmmoTypes.BOMB to 25f,
                        AmmoTypes.MISSILE to 50f,
                    ),
                ),
            seatOffsets = listOf(Vec.ZERO, Vec(0.0, 3.0, 0.0)),
        )
}
