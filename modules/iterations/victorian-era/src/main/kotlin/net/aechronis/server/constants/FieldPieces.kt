package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.AnimatedPart
import net.aechronis.combat.objects.AutomaticFieldPiece
import net.aechronis.combat.objects.Cannon
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec
import net.minestom.server.particle.Particle

object FieldPieces {
    val kruppC64 =
        Cannon(
            name = "krupp-c64-field-gun",
            itemName = Component.text("Krupp C64 Field Gun", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            // The exported cubes use 0.8 scale to stay inside Java model bounds.
            scale = 1.25,
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(
                            offset = Vec(0.0, 0.75, 0.0),
                            size = Vec(0.9, 0.75, 1.5),
                        ),
                    ),
                ),
            health =
                Health(
                    300F,
                    mapOf(
                        AmmoTypes.NORMAL to 3F,
                        AmmoTypes.EXPLOSIVE to 75F,
                        AmmoTypes.BOMB to 150F,
                        AmmoTypes.MISSILE to 150F,
                    ),
                ),
            placeTime = 3000,
            ammo = Ammo.artilleryShell,
            maxAmmo = 1,
            projectileModel = Ammo.artilleryShell.itemModel,
            projectileName = Component.text("Krupp C64 Field Gun"),
            projectileSpeed = 4.0,
            projectileExplosionRadius = 4,
            projectileExplosionFire = 0.0,
            projectileExplosionDamage = 80F,
            barrelTipOffset = Vec(0.0, 1.0, 1.8),
            reloadTime = 10000,
            projectileTrailParticle = Particle.SMOKE,
            projectileMaxRange = 256.0,
            moveSpeed = 0.035,
            turnSpeed = 1F,
            maxYaw = 20F,
            animatedParts =
                listOf(
                    AnimatedPart.rollingWheel(
                        model = "aechronis:krupp-c64-field-gun-wheel-left",
                        offset = Vec(-0.6875, 0.65, 0.04375),
                        radius = 0.65,
                    ),
                    AnimatedPart.rollingWheel(
                        model = "aechronis:krupp-c64-field-gun-wheel-right",
                        offset = Vec(0.6875, 0.65, 0.04375),
                        radius = 0.65,
                    ),
                ),
        )

    // Supplies firing behavior only; the registered item is the wheeled field piece below.
    private val gatlingGunWeapon =
        Gun(
            name = "gatling-gun",
            itemName = Component.text("Gatling Gun", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 40,
            damage = 14F,
            automatic = true,
            sniper = false,
            cooldown = 150,
            reloadTime = 6000,
            recoilMin = 1F,
            recoilMax = 3F,
            spreadMin = 1.5F,
            spreadMax = 4F,
            maxRange = 128.0,
            bulletTrailParticle = Particle.SMOKE,
        )

    val gatlingGun =
        AutomaticFieldPiece(
            name = "gatling-gun",
            itemName = gatlingGunWeapon.itemName,
            // The exported cubes use 0.8 scale to stay inside Java model bounds.
            scale = 1.25,
            hitbox =
                Hitbox(
                    listOf(
                        HitboxPart(
                            offset = Vec(0.0, 0.85, 0.0),
                            size = Vec(0.85, 0.85, 1.75),
                        ),
                    ),
                ),
            health =
                Health(
                    300F,
                    mapOf(
                        AmmoTypes.NORMAL to 3F,
                        AmmoTypes.EXPLOSIVE to 75F,
                        AmmoTypes.BOMB to 150F,
                        AmmoTypes.MISSILE to 150F,
                    ),
                ),
            gun = gatlingGunWeapon,
            placeTime = 3000,
            barrelPivotOffset = Vec(0.0, 1.2109375, -0.021875),
            barrelTipOffset = Vec(0.0, 0.0, 1.721875),
            minPitch = -20F,
            maxPitch = 10F,
            moveSpeed = 0.035,
            turnSpeed = 1F,
            maxYaw = 12F,
            animatedParts =
                listOf(
                    AnimatedPart.rollingWheel(
                        model = "aechronis:gatling-gun-wheel-left",
                        offset = Vec(-0.6875, 0.65, 0.04375),
                        radius = 0.65,
                    ),
                    AnimatedPart.rollingWheel(
                        model = "aechronis:gatling-gun-wheel-right",
                        offset = Vec(0.6875, 0.65, 0.04375),
                        radius = 0.65,
                    ),
                ),
        )
}
