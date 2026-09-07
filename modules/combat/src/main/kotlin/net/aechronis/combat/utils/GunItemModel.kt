package net.aechronis.combat.utils

import net.minestom.server.component.DataComponents
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.CustomModelData
import net.minestom.server.item.component.Tool
import net.minestom.server.item.component.UseCooldown
import net.minestom.server.registry.RegistryTag

internal const val AIM_ENTRY_COOLDOWN_GROUP = "aechronis:aim_entry"
internal const val AIM_ENTRY_TICKS = 7

// instant breaking never enters the clients sustained mining state, which would
// suppress right-click input during automatic fire
internal val GUN_MINING_TOOL =
    Tool(listOf(Tool.Rule(RegistryTag.direct(Block.SCULK_VEIN.registryKey()), 1_000_000_000F, false)), 0F, 0, false)

internal fun gunItemModel(
    item: ItemStack,
    material: Material,
    model: String,
    aiming: Boolean = false,
    crouching: Boolean = false,
    enteringAim: Boolean = false,
): ItemStack {
    val targetMaterial = if (aiming) Material.CROSSBOW else material
    var result = item
    if (item.material() != targetMaterial) {
        // preserve resolved defaults too, important as the durability used as the ammo bar
        val components = item.components()
        val patch = components.toPatchBuilder()
        for (entry in targetMaterial.prototype().entrySet()) {
            if (!components.has(entry.component())) patch.remove(entry.component())
        }
        result = ItemStack.of(targetMaterial, item.amount(), patch.build())
    }
    result = result.withItemModel(model).with(DataComponents.TOOL, GUN_MINING_TOOL)
    result =
        if (enteringAim) {
            result.with(DataComponents.USE_COOLDOWN, UseCooldown((AIM_ENTRY_TICKS + 1) / 20F, AIM_ENTRY_COOLDOWN_GROUP))
        } else if (result.get(DataComponents.USE_COOLDOWN)?.cooldownGroup() == AIM_ENTRY_COOLDOWN_GROUP) {
            result.without(DataComponents.USE_COOLDOWN)
        } else {
            result
        }
    return if (aiming) {
        // standing 0, crouching 1
        val stance = if (crouching) 1F else 0F
        val data = result.get(DataComponents.CUSTOM_MODEL_DATA)
        val floats = data?.floats()?.toMutableList() ?: mutableListOf()
        if (floats.isEmpty()) floats.add(stance) else floats[0] = stance
        result
            .with(
                DataComponents.CUSTOM_MODEL_DATA,
                CustomModelData(floats, data?.flags() ?: emptyList(), data?.strings() ?: emptyList(), data?.colors() ?: emptyList()),
            ).with(DataComponents.CHARGED_PROJECTILES, listOf(ItemStack.of(Material.ARROW)))
    } else {
        result.without(DataComponents.CHARGED_PROJECTILES)
    }
}
