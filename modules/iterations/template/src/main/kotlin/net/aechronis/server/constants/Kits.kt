package net.aechronis.server.constants

import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.combat.objects.Gun
import net.aechronis.vanilla.objects.Bundle
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Kits {
    private const val PRIMARY_SLOT = 0
    private const val SECONDARY_SLOT = 1
    private const val MELEE_SLOT = 2
    private const val TOOL_SLOT = 3
    private const val PRIMARY_AMMO_SLOT = 9
    private const val SECONDARY_AMMO_SLOT = 10
    private const val FOOD_SLOT = 7
    private const val BLOCKS_SLOT = 8

    private val usMarineArmor =
        listOf(
            Armor.usMarineJacket,
            Armor.usMarineTrousers,
            Armor.usMarineBoots,
        )

    private val idfArmor =
        listOf(
            Armor.idfJacket,
            Armor.idfTrousers,
            Armor.idfBoots,
        )

    val all: List<ItemStack> by lazy {
        listOf(
            combatKit("M4A1 Rifleman Kit", Guns.m4a1, Guns.glock17, usMarineArmor),
            combatKit("AK-12 Rifleman Kit", Guns.ak12, Guns.vz61, idfArmor),
            combatKit("AK-74 Rifleman Kit", Guns.ak74, Guns.m9, idfArmor),
            combatKit("QBZ-95 Rifleman Kit", Guns.qbz95, Guns.glock17, idfArmor),
            combatKit("G3 Rifleman Kit", Guns.g3, Guns.m9, usMarineArmor),
            combatKit("AWP Sniper Kit", Guns.awp, Guns.glock17, usMarineArmor),
            combatKit("MG3 Support Kit", Guns.mg3, Guns.m9, usMarineArmor),
            combatKit("MP5 Breacher Kit", Guns.mp5, Guns.vz61, idfArmor),
        )
    }

    private fun combatKit(
        name: String,
        primary: Gun,
        secondary: Gun,
        armor: List<ArmorPiece>,
    ): ItemStack =
        namedBundle(
            name,
            mapOf(
                PRIMARY_SLOT to primary.toItemStack(),
                SECONDARY_SLOT to secondary.toItemStack(),
                MELEE_SLOT to Melees.baton.toItemStack(),
                TOOL_SLOT to ItemStack.of(Material.DIAMOND_PICKAXE),
                PRIMARY_AMMO_SLOT to primary.ammo.toItemStack().withAmount(64),
                SECONDARY_AMMO_SLOT to secondary.ammo.toItemStack().withAmount(32),
                FOOD_SLOT to ItemStack.of(Material.COOKED_BEEF, 64),
                BLOCKS_SLOT to ItemStack.of(Material.OAK_FENCE, 64),
            ) + armor.associate { it.slot.armorSlot() to it.toItemStack() },
        )

    private fun namedBundle(
        name: String,
        items: Map<Int, ItemStack>,
    ): ItemStack =
        Bundle(items)
            .makeBundle()
            .withCustomName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false))
}
