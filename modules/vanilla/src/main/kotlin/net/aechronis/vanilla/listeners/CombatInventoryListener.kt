package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EquipmentSlot
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

    private fun acceptsItem(
        slot: Int,
        item: ItemStack,
    ): Boolean {
        if (item.isAir) return true
        val equipmentSlot =
            when (slot) {
                PlayerInventoryUtils.HELMET_SLOT -> EquipmentSlot.HELMET
                PlayerInventoryUtils.CHESTPLATE_SLOT -> EquipmentSlot.CHESTPLATE
                PlayerInventoryUtils.LEGGINGS_SLOT -> EquipmentSlot.LEGGINGS
                PlayerInventoryUtils.BOOTS_SLOT -> EquipmentSlot.BOOTS
                else -> return true
            }
        return item.get(DataComponents.EQUIPPABLE)?.slot == equipmentSlot
    }

    private fun incomingItem(event: InventoryPreClickEvent): ItemStack =
        when (val click = event.click) {
            is Click.Left, is Click.Right, is Click.Drag -> event.player.inventory.cursorItem
            is Click.HotbarSwap -> event.player.inventory.getItemStack(click.hotbarSlot)
            is Click.OffhandSwap -> event.player.inventory.getItemStack(PlayerInventoryUtils.OFFHAND_SLOT)
            else -> ItemStack.AIR
        }

    fun onInventoryClick(event: InventoryPreClickEvent) {
        val incoming = incomingItem(event)
        if (incoming.isAir) return
        val invalidPlacement =
            when (val click = event.click) {
                is Click.Drag -> {
                    // Mixed drags report player slots after the opened container's slots.
                    val offset = if (event.inventory === event.player.inventory) 0 else event.inventory.size
                    click.slots().any { slot -> slot - offset in armorSlots && !acceptsItem(slot - offset, incoming) }
                }
                else -> event.inventory === event.player.inventory && !acceptsItem(event.slot, incoming)
            }
        if (invalidPlacement) event.isCancelled = true
    }

    fun onCreativeInventoryAction(event: CreativeInventoryActionEvent) {
        if (!acceptsItem(event.slot, event.clickedItem)) event.isCancelled = true
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
