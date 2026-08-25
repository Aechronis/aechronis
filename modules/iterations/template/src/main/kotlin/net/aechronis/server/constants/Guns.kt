package net.aechronis.server.constants

import net.aechronis.combat.objects.Gun
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec
import net.minestom.server.particle.Particle

object Guns {
    // RIFLES
    val m4a1 =
        Gun(
            name = "m4a1",
            itemName = Component.text("M4A1", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 30,
            damage = 12F,
            automatic = true,
            sniper = false,
            cooldown = 80,
            reloadTime = 2500,
            recoilMin = 1F,
            recoilMax = 4F,
            spreadMin = 0.1F,
            spreadMax = 2F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val ak12 =
        Gun(
            name = "ak12",
            itemName = Component.text("AK-12", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 30,
            damage = 9F,
            automatic = true,
            sniper = false,
            cooldown = 80,
            reloadTime = 3000,
            recoilMin = 1F,
            recoilMax = 4F,
            spreadMin = 0.1F,
            spreadMax = 2F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val qbz95 =
        Gun(
            name = "qbz-95",
            itemName = Component.text("QBZ-95", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 30,
            damage = 12F,
            automatic = true,
            sniper = false,
            cooldown = 80,
            reloadTime = 3000,
            recoilMin = 3F,
            recoilMax = 6F,
            spreadMin = 1F,
            spreadMax = 4F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val ak74 =
        Gun(
            name = "ak74",
            itemName = Component.text("AK-74", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 30,
            damage = 7F,
            automatic = true,
            sniper = false,
            cooldown = 80,
            reloadTime = 3200,
            recoilMin = 1F,
            recoilMax = 4F,
            spreadMin = 0.4F,
            spreadMax = 2F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val g3 =
        Gun(
            name = "g3",
            itemName = Component.text("G3", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 15,
            damage = 16F,
            automatic = false,
            sniper = false,
            cooldown = 450,
            reloadTime = 3000,
            recoilMin = 1F,
            recoilMax = 2F,
            spreadMin = 0F,
            spreadMax = 2F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    // SNIPERS
    val awp =
        Gun(
            name = "awp",
            itemName = Component.text("AWP", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 3,
            damage = 44F,
            automatic = false,
            sniper = true,
            cooldown = 1500,
            reloadTime = 4000,
            recoilMin = 8F,
            recoilMax = 10F,
            spreadMin = 0F,
            spreadMax = 1F,
            maxRange = 512.0,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.1, 1.2),
        )

    // PISTOLS
    val glock17 =
        Gun(
            name = "glock17",
            itemName = Component.text("Glock 17", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo9mm,
            maxAmmo = 17,
            damage = 4.5F,
            automatic = false,
            sniper = false,
            cooldown = 130,
            reloadTime = 2200,
            recoilMin = 0.5F,
            recoilMax = 2F,
            spreadMin = 0.4F,
            spreadMax = 2.5F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.15, 0.8),
        )

    val m9 =
        Gun(
            name = "m9",
            itemName = Component.text("M9", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo9mm,
            maxAmmo = 15,
            damage = 5F,
            automatic = false,
            sniper = false,
            cooldown = 150,
            reloadTime = 2000,
            recoilMin = 0.5F,
            recoilMax = 2F,
            spreadMin = 0.4F,
            spreadMax = 2.5F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.15, 0.8),
        )

    val mg3 =
        Gun(
            name = "mg3",
            itemName = Component.text("MG3", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 100,
            damage = 3.5F,
            automatic = true,
            sniper = false,
            cooldown = 50,
            reloadTime = 5000,
            recoilMin = 2F,
            recoilMax = 2F,
            spreadMin = 3F,
            spreadMax = 5F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.1, 1.1),
        )

    val planeMg3 =
        Gun(
            name = "plane-mg3",
            itemName = Component.text("Plane MachineGun", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mmExplosive,
            maxAmmo = 200,
            damage = 6F,
            automatic = true,
            sniper = false,
            cooldown = 50,
            reloadTime = 6000,
            recoilMin = 0F,
            recoilMax = 1F,
            spreadMin = 0F,
            spreadMax = 1F,
            soundFire = mg3.soundFire,
            soundReload = mg3.soundReload,
            itemModel = "aechronis:mg3",
            itemModelEmpty = "aechronis:mg3-empty",
            itemModelReloading = "aechronis:mg3-reloading",
            itemModelAiming = "aechronis:mg3-aiming",
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(1F),
            maxRange = 250.0,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.1),
        )

    val at4 =
        Gun(
            name = "at4",
            itemName = Component.text("AT4", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rocket,
            maxAmmo = 1,
            damage = 30F,
            automatic = false,
            sniper = false,
            cooldown = 1500,
            reloadTime = 5000,
            recoilMin = 8F,
            recoilMax = 10F,
            spreadMin = 0F,
            spreadMax = 1F,
            bulletTrailParticle = Particle.FLAME,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.2),
        )

    // 4
    val mp5 =
        Gun(
            name = "mp5",
            itemName = Component.text("MP5", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo9mm,
            maxAmmo = 30,
            damage = 3.5F,
            automatic = true,
            sniper = false,
            cooldown = 70,
            reloadTime = 2400,
            recoilMin = 1F,
            recoilMax = 3F,
            spreadMin = 1F,
            spreadMax = 2F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.1, 0.9),
        )

    val vz61 =
        Gun(
            name = "vz61",
            itemName = Component.text("Vz. 61", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo9mm,
            maxAmmo = 20,
            damage = 3.5F,
            automatic = true,
            sniper = false,
            cooldown = 60,
            reloadTime = 3000,
            recoilMin = 2F,
            recoilMax = 4F,
            spreadMin = 1F,
            spreadMax = 4F,
            bulletTrailParticle = Particle.DUST_COLOR_TRANSITION.withScale(0.2F),
            bulletTrailOffset = Vec(-0.3, -0.12, 0.85),
        )
}
