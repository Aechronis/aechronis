package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.PlaneWeapon
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec
import net.minestom.server.particle.Particle

object Planes {
    val f16Hitbox =
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
        )

    val f16Gun =
        Gun(
            name = "f16-gun",
            itemName = Component.empty(),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 30,
            damage = 15F,
            sniper = false,
            automatic = true,
            cooldown = 200,
            reloadTime = 0,
            recoilMin = 0F,
            recoilMax = 0F,
            spreadMin = 0F,
            spreadMax = 1F,
            bulletTrailParticle = Particle.ELECTRIC_SPARK,
        )

    val f16Weapon =
        PlaneWeapon(
            gun = f16Gun,
            firePoints = listOf(Vec(1.75, 0.0, 2.0), Vec(-1.75, 0.0, 2.0)),
        )

    val f16 =
        Plane(
            name = "f16",
            itemName = Component.text("F-16", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            hitbox = f16Hitbox,
            health =
                Health(
                    800f,
                    mapOf(
                        AmmoTypes.NORMAL to 2.5f,
                        AmmoTypes.EXPLOSIVE to 750f,
                        AmmoTypes.BOMB to 500f,
                        AmmoTypes.MISSILE to 750f,
                    ),
                ),
            placeTime = 2000,
            weapons = listOf(f16Weapon),
            scale = 7.0,
            speed = 2.0,
            turnSpeed = 0.09f,
            seatOffset = listOf(Vec(0.0, 1.5, 0.0)),
        )
}
