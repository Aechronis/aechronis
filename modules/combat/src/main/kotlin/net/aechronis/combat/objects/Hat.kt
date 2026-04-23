package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.Equippable
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
}
