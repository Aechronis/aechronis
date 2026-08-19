package net.aechronis.server.constants

import net.aechronis.combat.objects.Ammo
import net.aechronis.combat.objects.AmmoTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Ammo {
    val ammo762x39mm =
        Ammo(
            name = "762x39mm",
            ammoType = AmmoTypes.NORMAL,
            itemName = Component.text("7.62x39mm", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
        )

    val ammo9mm =
        Ammo(
            name = "9mm",
            ammoType = AmmoTypes.NORMAL,
            itemName = Component.text("9mm", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
        )

    val ammo762x39mmExplosive =
        Ammo(
            name = "762x39mm-explosive",
            ammoType = AmmoTypes.EXPLOSIVE,
            itemName = Component.text("Explosive 7.62x39mm", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "aechronis:762x39mm",
        )

    val tankShell =
        Ammo(
            name = "tank-shell",
            itemName = Component.text("Tank Shell", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammoType = AmmoTypes.EXPLOSIVE,
            itemModel = "aechronis:shell",
        )

    val rocket =
        Ammo(
            name = "rpg-rocket",
            itemName = Component.text("Rocket", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammoType = AmmoTypes.EXPLOSIVE,
            itemModel = "aechronis:rpg-rocket",
        )
}
