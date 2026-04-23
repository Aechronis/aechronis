package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack

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
    fun get(player: Player): Int {
        var count = 0

        val inventory = player.inventory

        for (i in 0 until inventory.size) {
            val item = inventory.getItemStack(i)
            if (item.isAir) continue

            if (item.getTag(Tags.name) == name) {
                count += item.amount()
            }
        }

        return count
    }

    fun take(
        player: Player,
        amount: Int,
    ) {
        var toRemove = amount
        for (i in 0 until player.inventory.size) {
            if (toRemove <= 0) break

            val item = player.inventory.getItemStack(i)
            if (item.getTag(Tags.name) != name) continue

            if (item.amount() <= toRemove) {
                toRemove -= item.amount()
                player.inventory.setItemStack(i, ItemStack.AIR)
            } else {
                player.inventory.setItemStack(
                    i,
                    item.withAmount(item.amount() - toRemove),
                )
                toRemove = 0
            }
        }
    }

    fun add(
        player: Player,
        amount: Int,
    ) {
        var toAdd = amount

        while (toAdd > 0) {
            val stackSize = minOf(16, toAdd)
            val stack = toItemStack().withAmount(stackSize)

            player.inventory.addItemStack(stack)

            toAdd -= stackSize
        }
    }
}
