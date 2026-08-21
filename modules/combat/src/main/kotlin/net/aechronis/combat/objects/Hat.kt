package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.item.ItemStack

class Hat(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    protection: Float = 0F,
    assetId: String? = null,
) : ArmorPiece(
        name = name,
        itemName = itemName,
        itemLore = itemLore,
        itemModel = itemModel,
        slot = EquipmentSlot.HELMET,
        protection = protection,
        assetId = assetId,
    ) {
    override fun toItemStack(): ItemStack = super.toItemStack().withMaxStackSize(1)
}
