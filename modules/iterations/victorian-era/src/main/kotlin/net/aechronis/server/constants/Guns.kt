package net.aechronis.server.constants

import net.aechronis.combat.objects.Gun
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec
import net.minestom.server.particle.Particle

object Guns {
    // A full uniform provides 70% protection, so these rifles deal 13.2-15 damage after armor.
    val dreyseNeedleGun =
        Gun(
            name = "dreyse-needle-gun",
            animatedViewModelProfile = 0,
            fireAnimationTicks = 6,
            itemName = Component.text("Dreyse Needle Gun", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 1,
            damage = 44F,
            automatic = false,
            sniper = false,
            cooldown = 1000,
            reloadTime = 3000,
            recoilMin = 2F,
            recoilMax = 4F,
            spreadMin = 0.3F,
            spreadMax = 2F,
            maxRange = 160.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val chassepot =
        Gun(
            name = "chassepot",
            animatedViewModelProfile = 1,
            fireAnimationTicks = 6,
            itemName = Component.text("Chassepot", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 1,
            damage = 46F,
            automatic = false,
            sniper = false,
            cooldown = 1000,
            reloadTime = 3200,
            recoilMin = 2F,
            recoilMax = 4F,
            spreadMin = 0.15F,
            spreadMax = 1.5F,
            maxRange = 224.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val martiniHenry =
        Gun(
            name = "martini-henry",
            animatedViewModelProfile = 2,
            fireAnimationTicks = 6,
            itemName = Component.text("Martini-Henry", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 1,
            damage = 50F,
            automatic = false,
            sniper = false,
            cooldown = 1000,
            reloadTime = 3600,
            recoilMin = 3F,
            recoilMax = 6F,
            spreadMin = 0.2F,
            spreadMax = 2F,
            maxRange = 224.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val werndlHolub =
        Gun(
            name = "m1867-werndl-holub",
            animatedViewModelProfile = 4,
            itemName = Component.text("M1867 Werndl-Holub", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 1,
            damage = 48F,
            automatic = false,
            sniper = false,
            cooldown = 1000,
            reloadTime = 3400,
            recoilMin = 3F,
            recoilMax = 5F,
            spreadMin = 0.25F,
            spreadMax = 2F,
            maxRange = 192.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val vetterliModel1870 =
        Gun(
            name = "vetterli-model-1870",
            animatedViewModelProfile = 5,
            itemName = Component.text("Vetterli Model 1870", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 1,
            damage = 45F,
            automatic = false,
            sniper = false,
            cooldown = 1000,
            reloadTime = 2800,
            recoilMin = 2F,
            recoilMax = 4F,
            spreadMin = 0.2F,
            spreadMax = 1.75F,
            maxRange = 192.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val berdanII =
        Gun(
            name = "berdan-ii-rifle",
            animatedViewModelProfile = 3,
            fireAnimationTicks = 6,
            itemName = Component.text("Berdan II Rifle", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 1,
            damage = 47F,
            automatic = false,
            sniper = false,
            cooldown = 1000,
            reloadTime = 3200,
            recoilMin = 2F,
            recoilMax = 5F,
            spreadMin = 0.15F,
            spreadMax = 1.5F,
            maxRange = 224.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val adamsMkIII =
        Gun(
            name = "adams-mkiii-revolver",
            animatedViewModelProfile = 6,
            itemName = Component.text("Adams MkIII Revolver", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.revolverCartridge,
            maxAmmo = 6,
            damage = 20F,
            automatic = false,
            sniper = false,
            cooldown = 450,
            reloadTime = 5000,
            recoilMin = 1F,
            recoilMax = 3F,
            spreadMin = 0.6F,
            spreadMax = 3F,
            maxRange = 64.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
            mountable = true,
        )

    val colt1851 =
        Gun(
            name = "colt-1851-navy-revolver",
            animatedViewModelProfile = 7,
            itemName = Component.text("Colt 1851 Navy Revolver", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.revolverCartridge,
            maxAmmo = 6,
            damage = 18F,
            automatic = false,
            sniper = false,
            cooldown = 600,
            reloadTime = 6000,
            recoilMin = 1F,
            recoilMax = 2.5F,
            spreadMin = 0.4F,
            spreadMax = 2.5F,
            maxRange = 72.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
            mountable = true,
        )

    // Two shots per reload; uses the existing single-projectile Gun behavior.
    val coachGun =
        Gun(
            name = "coach-gun",
            animatedViewModelProfile = 8,
            itemName = Component.text("Coach Gun (Short Double Barrel)", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.shotgunShell,
            maxAmmo = 2,
            damage = 48F,
            automatic = false,
            sniper = false,
            cooldown = 350,
            reloadTime = 4000,
            recoilMin = 4F,
            recoilMax = 7F,
            spreadMin = 2F,
            spreadMax = 6F,
            maxRange = 32.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
            mountable = true,
        )

    val mauserModel1871Jaegerbuechse =
        Gun(
            name = "mauser-model-1871-jaegerbuechse",
            animatedViewModelProfile = 10,
            fireAnimationTicks = 6,
            itemName = Component.text("Mauser Model 1871 Jägerbüchse", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 1,
            damage = 47F,
            automatic = false,
            sniper = false,
            cooldown = 1000,
            reloadTime = 3000,
            recoilMin = 2.5F,
            recoilMax = 4.5F,
            spreadMin = 0.2F,
            spreadMax = 1.75F,
            maxRange = 192.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
            mountable = true,
        )

    val chassepotCarbine =
        Gun(
            name = "chassepot-carbine",
            animatedViewModelProfile = 11,
            fireAnimationTicks = 6,
            itemName = Component.text("Chassepot Carbine", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 1,
            damage = 44F,
            automatic = false,
            sniper = false,
            cooldown = 1000,
            reloadTime = 2800,
            recoilMin = 2F,
            recoilMax = 4.5F,
            spreadMin = 0.25F,
            spreadMax = 1.8F,
            maxRange = 176.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
            mountable = true,
        )

    val mauserModel1871 =
        Gun(
            name = "mauser-model-1871",
            animatedViewModelProfile = 12,
            fireAnimationTicks = 6,
            itemName = Component.text("Mauser Model 1871", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.rifleCartridge,
            maxAmmo = 1,
            damage = 48F,
            automatic = false,
            sniper = false,
            cooldown = 1000,
            reloadTime = 3200,
            recoilMin = 2.5F,
            recoilMax = 5F,
            spreadMin = 0.15F,
            spreadMax = 1.5F,
            maxRange = 224.0,
            bulletTrailParticle = Particle.SMOKE,
            bulletTrailOffset = Vec(-0.3, -0.1, 1.0),
        )

    val all: List<Gun> =
        listOf(
            dreyseNeedleGun,
            chassepot,
            martiniHenry,
            werndlHolub,
            vetterliModel1870,
            berdanII,
            adamsMkIII,
            colt1851,
            coachGun,
            mauserModel1871Jaegerbuechse,
            chassepotCarbine,
            mauserModel1871,
        )
}
