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
            ammo = Ammo.tankShell,
            maxAmmo = 10,
            health =
                Health(
                    1000F,
                    mapOf(
                        AmmoTypes.NORMAL to 1f,
                        AmmoTypes.MISSILE to 1f,
                        AmmoTypes.BOMB to 1f,
                        AmmoTypes.EXPLOSIVE to 1f,
                    ),
                ),
            seatOffsets = listOf(Vec(0.0, 1.0, 0.0)),
        )
}
