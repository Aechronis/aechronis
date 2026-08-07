package net.aechronis.server.constants

import net.aechronis.combat.objects.Melee
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Melees {
    val baton =
        Melee(
            name = "baton",
            itemName = Component.text("Baton", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            damage = 10.0,
            attackSpeed = 2.0,
            knockback = 0.5,
        )
}
