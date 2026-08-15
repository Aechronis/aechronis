package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Gun
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
import net.minestom.server.particle.Particle

object Planes {
    private val fighterHitbox =
        Hitbox(
            listOf(
                HitboxPart(
                    offset = Vec(0.0, 0.0, -2.0),
                    size = Vec(1.0, 1.0, 8.0),
                ),
                HitboxPart(
                    offset = Vec(0.0, -0.5, 0.0),
                    size = Vec(8.0, 0.5, 2.0),
                ),
            ),
        )

    private val fighterGun =
        Gun(
            name = "fighter-gun",
            itemName = Component.empty(),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 30,
            damage = 10F,
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

    private val fighterWeapon =
        PlaneWeapon(
            gun = fighterGun,
            firePoints = listOf(Vec(4.0, -0.5, 6.0), Vec(-4.0, -0.5, 6.0)),
        )

    val fighter =
        Plane(
            name = "fighter",
            itemName = Component.text("Fighter", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            hitbox = fighterHitbox,
            health =
                Health(
                    800F,
                    mapOf(
                        AmmoTypes.NORMAL to 1f,
                        AmmoTypes.MISSILE to 1f,
                        AmmoTypes.BOMB to 1f,
                        AmmoTypes.EXPLOSIVE to 1f,
                    ),
                ),
            placeTime = 2000,
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 120,
            weapons = listOf(fighterWeapon),
            scale = 7.0,
        )

    val bomber =
        Plane(
            name = "bomber",
            itemName = Component.text("Bomber", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "aechronis:fighter",
            model = "aechronis:fighter",
            hitbox = fighterHitbox,
            health =
                Health(
                    800F,
                    mapOf(
                        AmmoTypes.NORMAL to 1f,
                        AmmoTypes.MISSILE to 1f,
                        AmmoTypes.BOMB to 1f,
                        AmmoTypes.EXPLOSIVE to 1f,
                    ),
                ),
            placeTime = 2000,
            ammo = Ammo.tankShell,
            maxAmmo = 10,
            bomb =
                PlaneBombWeapon(
                    projectileModel = "aechronis:m1a1-abrams-shell",
                    projectileName = Component.text("Bomber shell"),
                    releaseOffset = Vec(0.0, -1.5, 0.0),
                    projectileSpeed = 4.0,
                    projectileExplosionRadius = 4,
                    projectileExplosionFire = 0.1,
                    projectileExplosionDamage = 20f,
                    fireCooldown = 20_000,
                ),
            scale = 7.0,
        )
}
