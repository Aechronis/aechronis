package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Combat
import net.minestom.server.component.DataComponents
import net.minestom.server.event.inventory.CreativeInventoryActionEvent
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.PlayerInventory
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.ItemStack
import net.minestom.server.utils.inventory.PlayerInventoryUtils

object CombatInventoryListener {
    private val armorSlots = PlayerInventoryUtils.HELMET_SLOT..PlayerInventoryUtils.BOOTS_SLOT
    private val craftingSlots =
        PlayerInventoryUtils.CRAFT_SLOT_1..PlayerInventoryUtils.CRAFT_SLOT_4

    private fun isArmorItem(itemStack: ItemStack): Boolean =
        itemStack
            .get(DataComponents.EQUIPPABLE)
            ?.slot
            ?.isArmor == true

    private fun incomingItem(event: InventoryPreClickEvent): ItemStack =
        when (val click = event.click) {
            is Click.Left, is Click.Right -> event.player.inventory.cursorItem
            is Click.LeftDrag, is Click.RightDrag, is Click.MiddleDrag -> event.player.inventory.cursorItem
            is Click.HotbarSwap -> event.player.inventory.getItemStack(click.hotbarSlot)
            is Click.OffhandSwap -> event.player.inventory.getItemStack(PlayerInventoryUtils.OFFHAND_SLOT)
            else -> ItemStack.AIR
        }

    private fun targetsArmorSlot(event: InventoryPreClickEvent): Boolean {
        if (event.inventory !== event.player.inventory) return false

        return when (val click = event.click) {
            is Click.LeftDrag, is Click.RightDrag, is Click.MiddleDrag ->
                click.slots().any { it in armorSlots }
            else -> event.slot in armorSlots
        }
    }

    fun onInventoryClick(event: InventoryPreClickEvent) {
        if (!Combat.isInCombat(event.player)) return
        if (!targetsArmorSlot(event)) return

        val incoming = incomingItem(event)
        if (!incoming.isAir && !isArmorItem(incoming)) event.isCancelled = true
    }

    fun onCreativeInventoryAction(event: CreativeInventoryActionEvent) {
        if (!Combat.isInCombat(event.player)) return
        if (event.slot in armorSlots && !event.clickedItem.isAir && !isArmorItem(event.clickedItem)) {
            event.isCancelled = true
        }
    }

    fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.inventory !== event.player.inventory) return
        val inventory = event.inventory as? PlayerInventory ?: return

        for (slot in craftingSlots) {
            val item = inventory.getItemStack(slot)
            if (item.isAir) continue

            if (event.player.dropItem(item)) {
                inventory.setItemStack(slot, ItemStack.AIR)
            }
        }
    }

    fun init() {
        Vanilla.eventNode.addListener(InventoryPreClickEvent::class.java, ::onInventoryClick)
        Vanilla.eventNode.addListener(CreativeInventoryActionEvent::class.java, ::onCreativeInventoryAction)
        Vanilla.eventNode.addListener(InventoryCloseEvent::class.java, ::onInventoryClose)
    }
}
