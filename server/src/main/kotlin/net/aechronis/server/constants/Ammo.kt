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
            itemName = Component.text("7.62x39mm", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            ammoType = AmmoTypes.NORMAL,
        )
}
