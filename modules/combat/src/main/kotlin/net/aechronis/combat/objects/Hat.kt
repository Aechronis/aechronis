package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.component.Equippable
import net.minestom.server.item.component.TooltipDisplay
import net.minestom.server.item.enchant.Enchantment
import net.minestom.server.registry.RegistryTag

class Hat(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
) : Item(
        name,
        itemName,
        itemLore,
        itemModel,
    ) {
    private val equippable =
        Equippable(
            EquipmentSlot.HELMET,
            Equippable.DEFAULT_EQUIP_SOUND,
            null,
            null,
            RegistryTag.empty(),
            true,
            true,
            false,
            true,
            false,
            Equippable.DEFAULT_SHEARING_SOUND,
        )

    override fun toItemStack(): ItemStack =
        ItemStack
            .of(material)
            .withItemModel(itemModel)
            .withCustomName(itemName)
            .withLore(itemLore)
            .withTag(Tags.name, name)
            .with(DataComponents.EQUIPPABLE, equippable)
            .withMaxStackSize(1)
            // we cancel any movement of the item in hatlistener, but this stops it clientside
            .with(DataComponents.ENCHANTMENTS, EnchantmentList(Enchantment.BINDING_CURSE, 1))
            .with(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false)
            .with(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(false, setOf(DataComponents.ENCHANTMENTS)))
}
