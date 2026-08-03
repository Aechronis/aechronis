package net.aechronis.server.constants

import net.aechronis.combat.objects.ArmorPiece
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.entity.EquipmentSlot

object Armor {
    val usMarineJacket =
        ArmorPiece(
            name = "us-marine-jacket",
            itemName = Component.text("US Marine Jacket", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "minecraft:leather_chestplate",
            slot = EquipmentSlot.CHESTPLATE,
            protection = 0.4F,
            assetId = "aechronis:us-marpat-desert",
        )

    val usMarineTrousers =
        ArmorPiece(
            name = "us-marine-trousers",
            itemName = Component.text("US Marine Trousers", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "minecraft:leather_leggings",
            slot = EquipmentSlot.LEGGINGS,
            protection = 0.2F,
            assetId = "aechronis:us-marpat-desert",
        )

    val usMarineBoots =
        ArmorPiece(
            name = "us-marine-boots",
            itemName = Component.text("US Marine Boots", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "minecraft:leather_boots",
            slot = EquipmentSlot.BOOTS,
            protection = 0.1F,
            assetId = "aechronis:us-marpat-desert",
        )

    val idfJacket =
        ArmorPiece(
            name = "idf-jacket",
            itemName = Component.text("IDF Jacket", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "minecraft:leather_chestplate",
            slot = EquipmentSlot.CHESTPLATE,
            protection = 0.4F,
            assetId = "aechronis:idf",
        )

    val idfTrousers =
        ArmorPiece(
            name = "idf-trousers",
            itemName = Component.text("IDF Trousers", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "minecraft:leather_leggings",
            slot = EquipmentSlot.LEGGINGS,
            protection = 0.2F,
            assetId = "aechronis:idf",
        )

    val idfBoots =
        ArmorPiece(
            name = "idf-boots",
            itemName = Component.text("IDF Boots", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemModel = "minecraft:leather_boots",
            slot = EquipmentSlot.BOOTS,
            protection = 0.1F,
            assetId = "aechronis:idf",
        )
}
