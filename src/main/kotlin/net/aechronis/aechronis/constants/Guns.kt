package net.aechronis.aechronis.constants

import net.aechronis.combat.objects.Gun
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Guns {
    val ak74 =
        Gun(
            name = "ak74",
            itemName = Component.text("AK-74", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 30,
            damage = 25F,
            automatic = true,
            sniper = false,
            cooldown = 100,
            reloadTime = 2800,
            recoilMin = 3F,
            recoilMax = 8F,
            spreadMin = 0.4F,
            spreadMax = 3F,
        )

    val m4a1 =
        Gun(
            name = "m4a1",
            itemName = Component.text("M4A1", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 30,
            damage = 20F,
            automatic = true,
            sniper = false,
            cooldown = 75,
            reloadTime = 2800,
            recoilMin = 2F,
            recoilMax = 6F,
            spreadMin = 0.3F,
            spreadMax = 2F,
        )

    val m9 =
        Gun(
            name = "m9",
            itemName = Component.text("M9", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo9mm,
            maxAmmo = 15,
            damage = 15F,
            automatic = false,
            sniper = false,
            cooldown = 150,
            reloadTime = 2200,
            recoilMin = 1F,
            recoilMax = 4F,
            spreadMin = 0.4F,
            spreadMax = 2.5F,
        )
}
