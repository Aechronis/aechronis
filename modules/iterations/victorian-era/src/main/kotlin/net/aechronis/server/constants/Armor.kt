package net.aechronis.server.constants

import net.aechronis.combat.objects.ArmorPiece
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.entity.EquipmentSlot

object Armor {
    // All national uniforms share 40% + 20% + 10% protection.
    val prussiaJacket = jacket("prussia-jacket", "Prussian Jacket", "aechronis:prussia")
    val prussiaTrousers = trousers("prussia-trousers", "Prussian Trousers", "aechronis:prussia")
    val prussiaBoots = boots("prussia-boots", "Prussian Boots", "aechronis:prussia")

    val franceJacket = jacket("france-jacket", "French Jacket", "aechronis:france")
    val franceTrousers = trousers("france-trousers", "French Trousers", "aechronis:france")
    val franceBoots = boots("france-boots", "French Boots", "aechronis:france")

    val britainJacket = jacket("britain-jacket", "British Jacket", "aechronis:britain")
    val britainTrousers = trousers("britain-trousers", "British Trousers", "aechronis:britain")
    val britainBoots = boots("britain-boots", "British Boots", "aechronis:britain")

    val austriaJacket = jacket("austria-jacket", "Austrian Jacket", "aechronis:austria")
    val austriaTrousers = trousers("austria-trousers", "Austrian Trousers", "aechronis:austria")
    val austriaBoots = boots("austria-boots", "Austrian Boots", "aechronis:austria")

    val russiaJacket = jacket("russia-jacket", "Russian Jacket", "aechronis:russia")
    val russiaTrousers = trousers("russia-trousers", "Russian Trousers", "aechronis:russia")
    val russiaBoots = boots("russia-boots", "Russian Boots", "aechronis:russia")

    val ottomanJacket = jacket("ottoman-jacket", "Ottoman Jacket", "aechronis:ottoman")
    val ottomanTrousers = trousers("ottoman-trousers", "Ottoman Trousers", "aechronis:ottoman")
    val ottomanBoots = boots("ottoman-boots", "Ottoman Boots", "aechronis:ottoman")

    val italyJacket = jacket("italy-jacket", "Italian Jacket", "aechronis:italy")
    val italyTrousers = trousers("italy-trousers", "Italian Trousers", "aechronis:italy")
    val italyBoots = boots("italy-boots", "Italian Boots", "aechronis:italy")

    val all: List<ArmorPiece> =
        listOf(
            prussiaJacket,
            prussiaTrousers,
            prussiaBoots,
            franceJacket,
            franceTrousers,
            franceBoots,
            britainJacket,
            britainTrousers,
            britainBoots,
            austriaJacket,
            austriaTrousers,
            austriaBoots,
            russiaJacket,
            russiaTrousers,
            russiaBoots,
            ottomanJacket,
            ottomanTrousers,
            ottomanBoots,
            italyJacket,
            italyTrousers,
            italyBoots,
        )

    private fun jacket(
        name: String,
        displayName: String,
        assetId: String,
    ): ArmorPiece =
        ArmorPiece(
            name = name,
            itemName = itemName(displayName),
            slot = EquipmentSlot.CHESTPLATE,
            protection = 0.4F,
            assetId = assetId,
        )

    private fun trousers(
        name: String,
        displayName: String,
        assetId: String,
    ): ArmorPiece =
        ArmorPiece(
            name = name,
            itemName = itemName(displayName),
            slot = EquipmentSlot.LEGGINGS,
            protection = 0.2F,
            assetId = assetId,
        )

    private fun boots(
        name: String,
        displayName: String,
        assetId: String,
    ): ArmorPiece =
        ArmorPiece(
            name = name,
            itemName = itemName(displayName),
            slot = EquipmentSlot.BOOTS,
            protection = 0.1F,
            assetId = assetId,
        )

    private fun itemName(displayName: String): Component =
        Component.text(displayName, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
}
