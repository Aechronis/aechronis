package net.aechronis.server.constants

import net.aechronis.combat.objects.Melee
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Melees {
    val cavalrySabre =
        Melee(
            name = "cavalry-sabre",
            itemName = Component.text("Cavalry Sabre", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            damage = 16.0,
            attackSpeed = 1.6,
            knockback = 0.5,
            sweepable = true,
        )

    val navalCutlass =
        Melee(
            name = "naval-cutlass",
            itemName = Component.text("Naval Cutlass", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            damage = 14.0,
            attackSpeed = 2.0,
            knockback = 0.4,
            sweepable = true,
        )

    val all: List<Melee> = listOf(cavalrySabre, navalCutlass)
}
