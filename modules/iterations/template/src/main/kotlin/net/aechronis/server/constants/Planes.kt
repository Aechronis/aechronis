package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.PlaneBombWeapon
import net.aechronis.combat.objects.PlaneWeapon
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec

object Planes {
    val b2 =
        Plane(
            name = "b-2",
            itemName = Component.text("B-2 Spirit", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(
                            offset = Vec(0.0, -1.2, 0.0),
                            size = Vec(10.7, 1.1, 4.8),
                        ),
                    ),
                ),
            health =
                Health(
                    1200f,
                    mapOf(
                        AmmoTypes.NORMAL to 2.5f,
                        AmmoTypes.EXPLOSIVE to 15f,
                        AmmoTypes.BOMB to 750f,
                        AmmoTypes.MISSILE to 750f,
                    ),
                ),
            placeTime = 2000,
            bomb =
                PlaneBombWeapon(
                    projectileModel = "aechronis:m1a1-abrams-shell",
                    projectileName = Component.text("B-2 bomb"),
                    releaseOffset = Vec(0.0, -1.5, 0.0),
                    projectileSpeed = 4.0,
                    projectileExplosionRadius = 5,
                    projectileExplosionFire = 0.3,
                    projectileExplosionDamage = 150f,
                    fireCooldown = 2_000,
                ),
            throttleStep = 3f,
            throttleDecay = 0.15f,
            landingThrottle = 45f,
            minAirThrottle = 40f,
            scale = 7.5,
            speed = 1.5,
            turnSpeed = 0.045f,
            ammo = Ammo.tankShell,
            maxAmmo = 5,
            seatOffset = listOf(Vec(0.0, 1.5, 0.0)),
        )

    val f16 =
        Plane(
            name = "f16",
            itemName = Component.text("F-16", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(
                            offset = Vec(0.0, 0.2, -2.0),
                            size = Vec(1.0, 1.0, 6.0),
                        ),
                        HitboxPart(
                            offset = Vec(0.0, 0.5, -3.0),
                            size = Vec(5.0, 0.5, 2.0),
                        ),
                    ),
                ),
            health =
                Health(
                    800f,
                    mapOf(
                        AmmoTypes.NORMAL to 2.5f,
                        AmmoTypes.EXPLOSIVE to 15f,
                        AmmoTypes.BOMB to 750f,
                        AmmoTypes.MISSILE to 750f,
                    ),
                ),
            placeTime = 2000,
            throttleStep = 8f,
            throttleDecay = 0.5f,
            landingThrottle = 30f,
            minAirThrottle = 24f,
            weapons =
                listOf(
                    PlaneWeapon(
                        gun = Guns.planeMg3,
                        firePoints = listOf(Vec(1.75, 0.0, 2.0), Vec(-1.75, 0.0, 2.0)),
                    ),
                ),
            scale = 7.0,
            speed = 2.4,
            turnSpeed = 0.14f,
            ammo = Ammo.ammo762x39mmExplosive,
            maxAmmo = 200,
            seatOffset = listOf(Vec(0.0, 1.5, 0.0)),
        )

    val j20 =
        Plane(
            name = "j-20",
            itemName = Component.text("J-20", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(
                            offset = Vec(0.0, -0.4, -0.5),
                            size = Vec(1.2, 1.7, 6.2),
                        ),
                        HitboxPart(
                            offset = Vec(0.0, -0.4, -3.3),
                            size = Vec(4.5, 0.8, 1.6),
                        ),
                    ),
                ),
            health =
                Health(
                    950f,
                    mapOf(
                        AmmoTypes.NORMAL to 2.5f,
                        AmmoTypes.EXPLOSIVE to 15f,
                        AmmoTypes.BOMB to 750f,
                        AmmoTypes.MISSILE to 750f,
                    ),
                ),
            placeTime = 2000,
            throttleStep = 7f,
            throttleDecay = 0.4f,
            landingThrottle = 34f,
            minAirThrottle = 28f,
            weapons =
                listOf(
                    PlaneWeapon(
                        gun = Guns.planeMg3,
                        firePoints = listOf(Vec(1.5, 0.0, 2.0), Vec(-1.5, 0.0, 2.0)),
                    ),
                ),
            scale = 7.0,
            speed = 2.3,
            turnSpeed = 0.11f,
            ammo = Ammo.ammo762x39mmExplosive,
            maxAmmo = 200,
            seatOffset = listOf(Vec(0.0, 1.5, 0.0)),
        )

    val su34 =
        Plane(
            name = "su-34",
            itemName = Component.text("Su-34", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(
                            offset = Vec(0.0, -0.1, 0.0),
                            size = Vec(1.8, 2.8, 10.8),
                        ),
                        HitboxPart(
                            offset = Vec(0.0, -0.1, -5.3),
                            size = Vec(6.5, 1.0, 3.5),
                        ),
                    ),
                ),
            health =
                Health(
                    1400f,
                    mapOf(
                        AmmoTypes.NORMAL to 2.5f,
                        AmmoTypes.EXPLOSIVE to 15f,
                        AmmoTypes.BOMB to 750f,
                        AmmoTypes.MISSILE to 750f,
                    ),
                ),
            placeTime = 2000,
            bomb =
                PlaneBombWeapon(
                    projectileModel = "aechronis:m1a1-abrams-shell",
                    projectileName = Component.text("Su-34 bomb"),
                    releaseOffset = Vec(0.0, -1.5, 0.0),
                    projectileSpeed = 4.0,
                    projectileExplosionRadius = 5,
                    projectileExplosionFire = 0.1,
                    projectileExplosionDamage = 150f,
                    fireCooldown = 15_000,
                ),
            throttleStep = 4f,
            throttleDecay = 0.2f,
            landingThrottle = 42f,
            minAirThrottle = 36f,
            scale = 7.5,
            speed = 1.9,
            turnSpeed = 0.065f,
            ammo = Ammo.tankShell,
            maxAmmo = 5,
            seatOffset = listOf(Vec(0.0, 1.5, 0.0)),
        )

    val su57 =
        Plane(
            name = "su-57",
            itemName = Component.text("Su-57", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(
                            offset = Vec(0.0, -0.6, -1.0),
                            size = Vec(1.5, 2.0, 8.5),
                        ),
                        HitboxPart(
                            offset = Vec(0.0, -0.6, -4.5),
                            size = Vec(6.2, 1.5, 2.7),
                        ),
                    ),
                ),
            health =
                Health(
                    1000f,
                    mapOf(
                        AmmoTypes.NORMAL to 2.5f,
                        AmmoTypes.EXPLOSIVE to 15f,
                        AmmoTypes.BOMB to 750f,
                        AmmoTypes.MISSILE to 750f,
                    ),
                ),
            placeTime = 2000,
            throttleStep = 8f,
            throttleDecay = 0.5f,
            landingThrottle = 28f,
            minAirThrottle = 22f,
            weapons =
                listOf(
                    PlaneWeapon(
                        gun = Guns.planeMg3,
                        firePoints = listOf(Vec(1.4, 0.0, 2.2), Vec(-1.4, 0.0, 2.2)),
                    ),
                ),
            scale = 7.0,
            speed = 2.5,
            turnSpeed = 0.15f,
            ammo = Ammo.ammo762x39mmExplosive,
            maxAmmo = 200,
            seatOffset = listOf(Vec(0.0, 1.5, 0.0)),
        )
}
