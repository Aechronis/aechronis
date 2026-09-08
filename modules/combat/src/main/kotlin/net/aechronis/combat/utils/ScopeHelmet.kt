package net.aechronis.combat.utils

import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

private val scopeEquippable = requireNotNull(ItemStack.of(Material.CARVED_PUMPKIN).get(DataComponents.EQUIPPABLE))

internal fun scopeHelmet(helmet: ItemStack): ItemStack {
    // Keep the equipped hat (including its armor asset), or use the pack's
    // invisible sculk vein item. Only the client-side copy receives the scope overlay.
    val item =
        if (helmet.isAir) {
            ItemStack
                .of(
                    Material.SCULK_VEIN,
                ).withItemModel("aechronis:invisible")
                .withCustomName(Component.empty())
        } else {
            helmet
        }
    val equippable = item.get(DataComponents.EQUIPPABLE) ?: scopeEquippable
    return item.with(DataComponents.EQUIPPABLE, equippable.withCameraOverlay(scopeEquippable.cameraOverlay()))
}
