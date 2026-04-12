package net.aechronis.aechronis.constants

import net.aechronis.combat.objects.Gun
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Guns {
    val ak47 =
        Gun(
            name = "ak47",
            itemName = Component.text("AK-47", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammo = Ammo.ammo762x39mm,
            maxAmmo = 30,
            damage = 30F,
            automatic = true,
            sniper = false,
            cooldown = 100,
            reloadTime = 3000,
            recoilMin = 3F,
            recoilMax = 10F,
            spreadMin = 0.5F,
            spreadMax = 3F,
        )
}
