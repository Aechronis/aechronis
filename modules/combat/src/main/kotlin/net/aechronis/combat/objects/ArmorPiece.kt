package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.EquipmentSlotGroup
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.AttributeList
import net.minestom.server.item.component.Equippable

class ArmorPiece(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    val slot: EquipmentSlot,
    val protection: Float,
    val assetId: String = "${Tags.NAMESPACE}:$name",
) : Item(
        name,
        itemName,
        itemLore,
        itemModel,
        Material.WARPED_FUNGUS_ON_A_STICK,
    ) {
    private val armorSlotGroup =
        when (slot) {
            EquipmentSlot.HELMET -> EquipmentSlotGroup.HEAD
            EquipmentSlot.CHESTPLATE -> EquipmentSlotGroup.CHEST
            EquipmentSlot.LEGGINGS -> EquipmentSlotGroup.LEGS
            EquipmentSlot.BOOTS -> EquipmentSlotGroup.FEET
            else -> error("Armor pieces must use an armor equipment slot")
        }

    private val attributeModifiers =
        AttributeList(
            AttributeList.Modifier(
                Attribute.ARMOR,
                AttributeModifier(
                    "${Tags.NAMESPACE}:$name-armor",
                    protection.toDouble() * 20.0,
                    AttributeOperation.ADD_VALUE,
                ),
                armorSlotGroup,
            ),
        )

    private val equippable =
        Equippable(
            slot,
            Equippable.DEFAULT_EQUIP_SOUND,
            assetId,
            null,
            null,
            true,
            true,
            false,
            true,
            false,
            Equippable.DEFAULT_SHEARING_SOUND,
        )

    override fun toItemStack(): ItemStack =
        super
            .toItemStack()
            .with(DataComponents.ATTRIBUTE_MODIFIERS, attributeModifiers)
            .with(DataComponents.EQUIPPABLE, equippable)

    companion object {
        fun getTotalProtection(player: Player): Float {
            val helmetProtection = (getFromItemStack(player.getEquipment(EquipmentSlot.HELMET)) as? ArmorPiece)?.protection ?: 0F
            val chestplateProtection = (getFromItemStack(player.getEquipment(EquipmentSlot.CHESTPLATE)) as? ArmorPiece)?.protection ?: 0F
            val leggingsProtection = (getFromItemStack(player.getEquipment(EquipmentSlot.LEGGINGS)) as? ArmorPiece)?.protection ?: 0F
            val bootsProtection = (getFromItemStack(player.getEquipment(EquipmentSlot.BOOTS)) as? ArmorPiece)?.protection ?: 0F

            return (1 - (helmetProtection + chestplateProtection + leggingsProtection + bootsProtection)).coerceIn(0F, 1F)
        }
    }
}
