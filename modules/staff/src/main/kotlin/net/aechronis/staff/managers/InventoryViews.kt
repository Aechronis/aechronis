package net.aechronis.staff.managers

import net.aechronis.server.modules.ModuleEvents
import net.aechronis.vanilla.managers.Commands
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.utils.inventory.PlayerInventoryUtils
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

object InventoryViews {
    // Storage keeps its slot numbers in the view, followed by the four armor slots and the off-hand.
    const val MIRRORED_SLOTS = 41
    private const val VIEW_HELMET_SLOT = 36
    private const val VIEW_BOOTS_SLOT = 39
    private const val VIEW_OFFHAND_SLOT = 40
    private val viewing = ConcurrentHashMap<Inventory, Player>()
    private val viewingLock = Any()
    private val synchronizingInventories =
        Collections.newSetFromMap(IdentityHashMap<AbstractInventory, Boolean>())
    private val enderChestViews = ConcurrentHashMap<Player, Inventory>()

    fun open(
        viewer: Player,
        target: Player,
    ) {
        val inv = Inventory(InventoryType.CHEST_6_ROW, Component.text("${target.username}'s inventory"))
        synchronized(viewingLock) {
            viewing[inv] = target
            withoutSynchronization(inv) {
                for (slot in 0 until MIRRORED_SLOTS) {
                    inv.setItemStack(slot, target.inventory.getItemStack(playerSlot(slot)))
                }
            }
        }
        if (!viewer.openInventory(inv)) {
            removeView(inv)
        }
    }

    internal fun synchronizeInventoryChange(
        inventory: AbstractInventory,
        slot: Int,
        item: ItemStack,
    ) {
        synchronized(viewingLock) {
            if (inventory in synchronizingInventories) return

            val sourceView = inventory as? Inventory
            val target = sourceView?.let(viewing::get)
            if (target != null) {
                if (slot !in 0 until MIRRORED_SLOTS) return
                setWithoutSynchronization(target.inventory, playerSlot(slot), item)
                for ((view, viewedPlayer) in viewing) {
                    if (view !== sourceView && viewedPlayer === target) {
                        setWithoutSynchronization(view, slot, item)
                    }
                }
                return
            }

            val viewSlot = viewSlot(slot) ?: return
            for ((view, viewedPlayer) in viewing) {
                if (viewedPlayer.inventory === inventory) {
                    setWithoutSynchronization(view, viewSlot, item)
                }
            }
        }
    }

    private fun playerSlot(viewSlot: Int): Int =
        when (viewSlot) {
            in VIEW_HELMET_SLOT..VIEW_BOOTS_SLOT -> viewSlot - VIEW_HELMET_SLOT + PlayerInventoryUtils.HELMET_SLOT
            VIEW_OFFHAND_SLOT -> PlayerInventoryUtils.OFFHAND_SLOT
            else -> viewSlot
        }

    private fun viewSlot(playerSlot: Int): Int? =
        when (playerSlot) {
            in 0 until VIEW_HELMET_SLOT -> playerSlot
            in PlayerInventoryUtils.HELMET_SLOT..PlayerInventoryUtils.BOOTS_SLOT ->
                playerSlot - PlayerInventoryUtils.HELMET_SLOT + VIEW_HELMET_SLOT
            PlayerInventoryUtils.OFFHAND_SLOT -> VIEW_OFFHAND_SLOT
            else -> null
        }

    internal fun removeView(inventory: Inventory): Boolean =
        synchronized(viewingLock) {
            viewing.remove(inventory) != null
        }

    private fun setWithoutSynchronization(
        inventory: AbstractInventory,
        slot: Int,
        item: ItemStack,
    ) = withoutSynchronization(inventory) {
        inventory.setItemStack(slot, item)
    }

    private inline fun withoutSynchronization(
        inventory: AbstractInventory,
        action: () -> Unit,
    ) {
        synchronizingInventories.add(inventory)
        try {
            action()
        } finally {
            synchronizingInventories.remove(inventory)
        }
    }

    fun openEnderChest(
        viewer: Player,
        target: Player,
    ) {
        Commands.openEnderChest(viewer, target)
        val inventory = Commands.getEnderChest(target)
        if (viewer.openInventory === inventory) enderChestViews[viewer] = inventory
    }

    private fun onClose(event: InventoryCloseEvent) {
        enderChestViews.remove(event.player, event.inventory)
        val inventory = event.inventory as? Inventory ?: return
        returnExtraItems(inventory, event.player)
    }

    private fun returnExtraItems(
        inventory: Inventory,
        player: Player,
    ) {
        if (!removeView(inventory)) return
        for (slot in MIRRORED_SLOTS until inventory.size) {
            val stack = inventory.getItemStack(slot)
            inventory.setItemStack(slot, ItemStack.AIR)
            if (!stack.isAir && !player.inventory.addItemStack(stack)) player.dropItem(stack)
        }
    }

    private fun closeView(inventory: Inventory) {
        inventory.viewers.toList().forEach { viewer ->
            viewer.closeInventory()
            // Lifecycle teardown may already have quiesced the close-event listener.
            returnExtraItems(inventory, viewer)
        }
        removeView(inventory)
    }

    private fun closeViewsOf(player: Player) {
        val inventories = synchronized(viewingLock) { viewing.filterValues { it === player }.keys.toList() }
        inventories.forEach(::closeView)
        enderChestViews.remove(player)
    }

    fun closeAll() {
        viewing.keys.toList().forEach(::closeView)
        enderChestViews.toMap().forEach { (viewer, inventory) ->
            if (viewer.openInventory === inventory) viewer.closeInventory()
        }
        enderChestViews.clear()
        synchronized(viewingLock) { viewing.clear() }
        synchronizingInventories.clear()
    }

    fun init() {
        // Close staff views before Vanilla snapshots and removes the disconnecting player's data.
        val node = EventNode.all("staff-inventory-views").setPriority(-100)
        node.addListener(InventoryItemChangeEvent::class.java) { event ->
            synchronizeInventoryChange(event.inventory, event.slot, event.newItem)
        }
        node.addListener(InventoryCloseEvent::class.java, ::onClose)
        node.addListener(PlayerDisconnectEvent::class.java) { event -> closeViewsOf(event.player) }
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), node)
    }
}
