package net.aechronis.server.constants

import net.aechronis.combat.objects.ArmorPiece
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.entity.EquipmentSlot

object Armor {
    val usMarineJacket = jacket("us-marine-jacket", "US Marine Jacket", "aechronis:us-marpat-desert")
    val usMarineTrousers = trousers("us-marine-trousers", "US Marine Trousers", "aechronis:us-marpat-desert")
    val usMarineBoots = boots("us-marine-boots", "US Marine Boots", "aechronis:us-marpat-desert")

    val idfJacket = jacket("idf-jacket", "IDF Jacket", "aechronis:idf")
    val idfTrousers = trousers("idf-trousers", "IDF Trousers", "aechronis:idf")
    val idfBoots = boots("idf-boots", "IDF Boots", "aechronis:idf")

    val chineseArmyJacket = jacket("chinese-army-jacket", "Chinese Army Jacket", "aechronis:chinese-army")
    val chineseArmyTrousers = trousers("chinese-army-trousers", "Chinese Army Trousers", "aechronis:chinese-army")
    val chineseArmyBoots = boots("chinese-army-boots", "Chinese Army Boots", "aechronis:chinese-army")

    val iranJacket = jacket("iran-jacket", "Iran Jacket", "aechronis:iran")
    val iranTrousers = trousers("iran-trousers", "Iran Trousers", "aechronis:iran")
    val iranBoots = boots("iran-boots", "Iran Boots", "aechronis:iran")

    val iraqJacket = jacket("iraq-jacket", "Iraq Jacket", "aechronis:iraq")
    val iraqTrousers = trousers("iraq-trousers", "Iraq Trousers", "aechronis:iraq")
    val iraqBoots = boots("iraq-boots", "Iraq Boots", "aechronis:iraq")

    val kurdJacket = jacket("kurd-jacket", "Kurd Jacket", "aechronis:kurd")
    val kurdTrousers = trousers("kurd-trousers", "Kurd Trousers", "aechronis:kurd")
    val kurdBoots = boots("kurd-boots", "Kurd Boots", "aechronis:kurd")

    val lebanonInsurgentJacket =
        jacket("lebanon-insurgent-jacket", "Lebanon Insurgent Jacket", "aechronis:lebanon-insurgent")
    val lebanonInsurgentTrousers =
        trousers("lebanon-insurgent-trousers", "Lebanon Insurgent Trousers", "aechronis:lebanon-insurgent")
    val lebanonInsurgentBoots =
        boots("lebanon-insurgent-boots", "Lebanon Insurgent Boots", "aechronis:lebanon-insurgent")

    val palestineInsurgentJacket =
        jacket("palestine-insurgent-jacket", "Palestine Insurgent Jacket", "aechronis:palestine-insurgent")
    val palestineInsurgentTrousers =
        trousers("palestine-insurgent-trousers", "Palestine Insurgent Trousers", "aechronis:palestine-insurgent")
    val palestineInsurgentBoots =
        boots("palestine-insurgent-boots", "Palestine Insurgent Boots", "aechronis:palestine-insurgent")

    val russiaArmyJacket = jacket("russia-army-jacket", "Russia Army Jacket", "aechronis:russia-army")
    val russiaArmyTrousers = trousers("russia-army-trousers", "Russia Army Trousers", "aechronis:russia-army")
    val russiaArmyBoots = boots("russia-army-boots", "Russia Army Boots", "aechronis:russia-army")

    val syriaJacket = jacket("syria-jacket", "Syria Jacket", "aechronis:syria")
    val syriaTrousers = trousers("syria-trousers", "Syria Trousers", "aechronis:syria")
    val syriaBoots = boots("syria-boots", "Syria Boots", "aechronis:syria")

    val turkeyJacket = jacket("turkey-jacket", "Turkey Jacket", "aechronis:turkey")
    val turkeyTrousers = trousers("turkey-trousers", "Turkey Trousers", "aechronis:turkey")
    val turkeyBoots = boots("turkey-boots", "Turkey Boots", "aechronis:turkey")

    val all: List<ArmorPiece> =
        listOf(
            usMarineJacket,
            usMarineTrousers,
            usMarineBoots,
            idfJacket,
            idfTrousers,
            idfBoots,
            chineseArmyJacket,
            chineseArmyTrousers,
            chineseArmyBoots,
            iranJacket,
            iranTrousers,
            iranBoots,
            iraqJacket,
            iraqTrousers,
            iraqBoots,
            kurdJacket,
            kurdTrousers,
            kurdBoots,
            lebanonInsurgentJacket,
            lebanonInsurgentTrousers,
            lebanonInsurgentBoots,
            palestineInsurgentJacket,
            palestineInsurgentTrousers,
            palestineInsurgentBoots,
            russiaArmyJacket,
            russiaArmyTrousers,
            russiaArmyBoots,
            syriaJacket,
            syriaTrousers,
            syriaBoots,
            turkeyJacket,
            turkeyTrousers,
            turkeyBoots,
        )

    private fun jacket(
        name: String,
        displayName: String,
        assetId: String,
    ): ArmorPiece =
        ArmorPiece(
            name = name,
            itemName = itemName(displayName),
            itemModel = "minecraft:leather_chestplate",
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
            itemModel = "minecraft:leather_leggings",
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
            itemModel = "minecraft:leather_boots",
            slot = EquipmentSlot.BOOTS,
            protection = 0.1F,
            assetId = assetId,
        )

    private fun itemName(displayName: String): Component =
        Component.text(displayName, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
}
