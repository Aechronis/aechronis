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
            maxSpeed = 0.4f,
            ammo = Ammo.tankShell,
            maxAmmo = 5,
            health =
                Health(
                    1500f,
                    mapOf(
                        AmmoTypes.NORMAL to 0f,
                        AmmoTypes.EXPLOSIVE to 50f,
                        AmmoTypes.BOMB to 500f,
                        AmmoTypes.MISSILE to 750f,
                    ),
                ),
            projectileModel = "aechronis:shell",
        )

    // Starter configuration for the T-90 body/turret/barrel models.
    val t90 =
        Tank(
            name = "t-90",
            itemName = Component.text("T-90", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            model = "aechronis:t-90",
            hitbox =
                Hitbox(
                    listOf(
                        // Body model bounds after applying its display scale (6.0).
                        HitboxPart(
                            offset = Vec(0.0, -1.9, -0.25),
                            size = Vec(2.1, 0.9, 4.5),
                        ),
                    ),
                ),
            scale = 6.0,
            maxSpeed = 0.6f,
            ammo = Ammo.tankShell,
            maxAmmo = 2,
            health =
                Health(
                    1200f,
                    mapOf(
                        AmmoTypes.NORMAL to 0f,
                        AmmoTypes.EXPLOSIVE to 50f,
                        AmmoTypes.BOMB to 500f,
                        AmmoTypes.MISSILE to 750f,
                    ),
                ),
            projectileModel = "aechronis:shell",
            projectileName = Component.text("T-90 cannon"),
            fireCooldown = 10000,
            barrelTipOffset = Vec(0.0, 0.0, 5.0),
        )
}
