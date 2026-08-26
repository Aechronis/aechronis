package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.utils.inventory.PlayerInventoryUtils

enum class AmmoTypes {
    NORMAL, // rifle ammo etc
    EXPLOSIVE, // anti tank ammo
    MISSILE, // some vehicle ammos e.g. jet, hele
    BOMB, // other vehicle type
}

class Ammo(
    name: String,
    val ammoType: AmmoTypes,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
) : Item(
        name,
        itemName,
        itemLore,
        itemModel,
    ) {
    operator fun get(player: Player): Int =
        reloadableSlots.sumOf { slot ->
            player.inventory
                .getItemStack(slot)
                .takeIf { it.getTag(Tags.name) == name }
                ?.amount() ?: 0
        }

    operator fun set(
        player: Player,
        amount: Int,
    ) {
        val diff = amount - this[player]
        when {
            diff > 0 -> player.inventory.addItemStack(toItemStack().withAmount(diff))
            diff < 0 -> removeFromReloadableSlots(player, -diff)
        }
    }

    private fun removeFromReloadableSlots(
        player: Player,
        amount: Int,
    ) {
        var remaining = amount
        for (slot in reloadableSlots) {
            val itemStack = player.inventory.getItemStack(slot)
            if (itemStack.getTag(Tags.name) != name) continue

            val removed = minOf(remaining, itemStack.amount())
            val replacement = if (removed == itemStack.amount()) ItemStack.AIR else itemStack.withAmount(itemStack.amount() - removed)
            player.inventory.setItemStack(slot, replacement)
            remaining -= removed
            if (remaining == 0) return
        }
    }

    private companion object {
        val reloadableSlots = 0 until PlayerInventoryUtils.CRAFT_RESULT
    }
}
