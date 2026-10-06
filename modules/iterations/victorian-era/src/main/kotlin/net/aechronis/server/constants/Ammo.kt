package net.aechronis.server.constants

import net.aechronis.combat.objects.Ammo
import net.aechronis.combat.objects.AmmoTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object Ammo {
    val rifleCartridge =
        Ammo(
            name = "rifle-cartridge",
            ammoType = AmmoTypes.NORMAL,
            itemName = Component.text("Rifle Cartridge", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
        )

    val revolverCartridge =
        Ammo(
            name = "revolver-cartridge",
            ammoType = AmmoTypes.NORMAL,
            itemName = Component.text("Revolver Cartridge", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
        )

    val shotgunShell =
        Ammo(
            name = "shotgun-shell",
            ammoType = AmmoTypes.NORMAL,
            itemName = Component.text("Shotgun Shell", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
        )

    val artilleryShell =
        Ammo(
            name = "artillery-shell",
            ammoType = AmmoTypes.EXPLOSIVE,
            itemName = Component.text("Artillery Shell", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
        )

    val gatlingFeedCase =
        Ammo(
            name = "gatling-feed-case",
            ammoType = AmmoTypes.NORMAL,
            itemName = Component.text("Gatling Feed Case", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemLore = listOf(Component.text("40 rounds", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)),
        )

    val maximAmmunitionBelt =
        Ammo(
            name = "maxim-ammunition-belt",
            ammoType = AmmoTypes.NORMAL,
            itemName = Component.text("Maxim Ammunition Belt", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemLore = listOf(Component.text("100 rounds", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)),
        )

    val all: List<Ammo> = listOf(rifleCartridge, revolverCartridge, shotgunShell, artilleryShell, gatlingFeedCase, maximAmmunitionBelt)
}
