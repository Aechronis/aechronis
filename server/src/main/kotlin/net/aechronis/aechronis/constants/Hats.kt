package net.aechronis.aechronis.constants

import net.aechronis.combat.objects.Hat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Hats {
    val gasMask =
        Hat(
            name = "gas-mask",
            itemName = Component.text("Gas mask", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
        )
}
