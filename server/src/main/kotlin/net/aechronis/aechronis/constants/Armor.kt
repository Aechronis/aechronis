package net.aechronis.aechronis.constants

import net.aechronis.combat.objects.ArmorPiece
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.entity.EquipmentSlot

object Armor {
    val jacket =
        ArmorPiece(
            name = "jacket",
            itemName = Component.text("Jacket", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            slot = EquipmentSlot.CHESTPLATE,
            protection = 0.25F,
            assetId = "aechronis:uniform",
        )

    val trousers =
        ArmorPiece(
            name = "trousers",
            itemName = Component.text("Trousers", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            slot = EquipmentSlot.LEGGINGS,
            protection = 0.2F,
            assetId = "aechronis:uniform",
        )

    val boots =
        ArmorPiece(
            name = "boots",
            itemName = Component.text("Boots", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            slot = EquipmentSlot.BOOTS,
            protection = 0.1F,
            assetId = "aechronis:uniform",
        )
}
