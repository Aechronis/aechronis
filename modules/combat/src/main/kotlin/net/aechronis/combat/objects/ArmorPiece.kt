package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.Equippable
import net.minestom.server.registry.RegistryTag

class ArmorPiece(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.namespace}:$name",
    val slot: EquipmentSlot,
    val protection: Float,
    val assetId: String = "${Tags.namespace}:$name",
) : Item(
        name,
        itemName,
        itemLore,
        itemModel,
        Material.WARPED_FUNGUS_ON_A_STICK,
    ) {
    private val equippable =
        Equippable(
            slot,
            Equippable.DEFAULT_EQUIP_SOUND,
            assetId,
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

    companion object {
        fun getTotalProtection(player: Player): Float {
            val chestplateProtection = (getFromItemStack(player.getEquipment(EquipmentSlot.CHESTPLATE)) as? ArmorPiece)?.protection ?: 0F
            val leggingsProtection = (getFromItemStack(player.getEquipment(EquipmentSlot.LEGGINGS)) as? ArmorPiece)?.protection ?: 0F
            val bootsProtection = (getFromItemStack(player.getEquipment(EquipmentSlot.BOOTS)) as? ArmorPiece)?.protection ?: 0F

            return 1 - (chestplateProtection + leggingsProtection + bootsProtection)
        }
    }
}
