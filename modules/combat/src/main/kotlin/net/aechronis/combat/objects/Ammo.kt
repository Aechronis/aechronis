package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.inventory.TransactionOption

class Ammo(
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
    operator fun get(player: Player): Int =
        player.inventory.itemStacks
            .filter { it.getTag(Tags.name) == name }
            .sumOf { it.amount() }

    operator fun set(
        player: Player,
        amount: Int,
    ) {
        val diff = amount - this[player]
        when {
            diff > 0 -> player.inventory.addItemStack(toItemStack().withAmount(diff))
            diff < 0 -> player.inventory.takeItemStack(toItemStack().withAmount(-diff), TransactionOption.ALL)
        }
    }
}
