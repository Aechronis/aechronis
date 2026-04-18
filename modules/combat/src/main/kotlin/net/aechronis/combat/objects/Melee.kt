package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EquipmentSlotGroup
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.AttributeList

class Melee(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.namespace}:$name",
    val damage: Double,
    val attackSpeed: Double = 4.0,
    val knockback: Double = 0.4,
    val sweepable: Boolean = false,
) : Item(
        name,
        itemName,
        itemLore,
        itemModel,
    ) {
    override fun toItemStack(): ItemStack {
        val modifiers =
            mutableListOf(
                AttributeList.Modifier(
                    Attribute.ATTACK_DAMAGE,
                    AttributeModifier(
                        "${Tags.namespace}:attack_damage",
                        damage,
                        AttributeOperation.ADD_VALUE,
                    ),
                    EquipmentSlotGroup.MAIN_HAND,
                ),
                AttributeList.Modifier(
                    Attribute.ATTACK_SPEED,
                    AttributeModifier(
                        "${Tags.namespace}:attack_speed",
                        attackSpeed - 4.0,
                        AttributeOperation.ADD_VALUE,
                    ),
                    EquipmentSlotGroup.MAIN_HAND,
                ),
            )

        return ItemStack
            .of(material)
            .withItemModel(itemModel)
            .withCustomName(itemName)
            .withLore(itemLore)
            .withTag(Tags.name, name)
            .with(DataComponents.ATTRIBUTE_MODIFIERS, AttributeList(modifiers))
    }
}
