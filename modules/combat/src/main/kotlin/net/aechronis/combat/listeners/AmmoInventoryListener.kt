package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Ammo
import net.aechronis.combat.objects.Item
import net.minestom.server.event.inventory.CreativeInventoryActionEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerSwapItemEvent
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.ItemStack
import net.minestom.server.utils.inventory.PlayerInventoryUtils

object AmmoInventoryListener {
    private val armorSlots = PlayerInventoryUtils.HELMET_SLOT..PlayerInventoryUtils.BOOTS_SLOT

    private fun isRestrictedSlot(slot: Int): Boolean = slot in armorSlots || slot == PlayerInventoryUtils.OFFHAND_SLOT

    private fun isAmmo(itemStack: ItemStack): Boolean = Item.getFromItemStack(itemStack) is Ammo

    private fun incomingItem(event: InventoryPreClickEvent): ItemStack =
        when (val click = event.click) {
            is Click.Left, is Click.Right -> event.player.inventory.cursorItem
            is Click.LeftDrag, is Click.RightDrag, is Click.MiddleDrag -> event.player.inventory.cursorItem
            is Click.HotbarSwap -> event.player.inventory.getItemStack(click.hotbarSlot)
            else -> ItemStack.AIR
        }

    private fun targetsRestrictedSlot(event: InventoryPreClickEvent): Boolean {
        if (event.inventory !== event.player.inventory) return false

        return when (val click = event.click) {
            is Click.LeftDrag, is Click.RightDrag, is Click.MiddleDrag -> click.slots().any(::isRestrictedSlot)
            is Click.OffhandSwap -> true
            else -> isRestrictedSlot(event.slot)
        }
    }

    internal fun onInventoryClick(event: InventoryPreClickEvent) {
        if (event.inventory !== event.player.inventory) return

        if (event.click is Click.OffhandSwap) {
            val offhandItem = event.player.inventory.getItemStack(PlayerInventoryUtils.OFFHAND_SLOT)
            if (isAmmo(event.clickedItem) || (event.slot in armorSlots && isAmmo(offhandItem))) {
                event.isCancelled = true
            }
            return
        }

        if (targetsRestrictedSlot(event) && isAmmo(incomingItem(event))) event.isCancelled = true
    }

    internal fun onPlayerSwapItem(event: PlayerSwapItemEvent) {
        if (isAmmo(event.mainHandItem)) event.isCancelled = true
    }

    internal fun onCreativeInventoryAction(event: CreativeInventoryActionEvent) {
        if (isRestrictedSlot(event.slot) && isAmmo(event.clickedItem)) event.isCancelled = true
    }

    fun init() {
        Combat.eventNode.addListener(InventoryPreClickEvent::class.java, ::onInventoryClick)
        Combat.eventNode.addListener(PlayerSwapItemEvent::class.java, ::onPlayerSwapItem)
        Combat.eventNode.addListener(CreativeInventoryActionEvent::class.java, ::onCreativeInventoryAction)
    }
}
