package net.aechronis.crates.listeners

import net.aechronis.crates.Crates
import net.aechronis.server.modules.ModuleEvents
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventNode
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerHandAnimationEvent
import net.minestom.server.event.player.PlayerStartDiggingEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.inventory.Inventory

object CratesListener {
    // Runs before guard and nodes protection so crates at spawn or in towns stay usable, and
    // cancelling here keeps the block's own GUI (shulker, chest) and breaking from running.
    private val eventNode = EventNode.all("crates").setPriority(-2000)

    private fun onInteract(event: PlayerBlockInteractEvent) {
        val crate = Crates.crateAt(event.instance, event.blockPosition) ?: return
        event.isCancelled = true
        event.isBlockingItemUse = true
        if (event.hand != PlayerHand.MAIN) return
        if (event.player.isSneaking) Crates.openAll(event.player, crate) else Crates.openOne(event.player, crate)
    }

    private fun onStartDigging(event: PlayerStartDiggingEvent) {
        val crate = Crates.crateAt(event.instance, event.blockPosition) ?: return
        event.isCancelled = true
        Crates.preview(event.player, crate)
    }

    // Creative mode breaks instantly without a start-digging event.
    private fun onBreak(event: PlayerBlockBreakEvent) {
        val crate = Crates.crateAt(event.instance, event.blockPosition) ?: return
        event.isCancelled = true
        Crates.preview(event.player, crate)
    }

    private fun onPreClick(event: InventoryPreClickEvent) {
        // Clicks in the player's own section report their PlayerInventory but still act on the open preview.
        val inventory = event.player.openInventory as? Inventory ?: return
        if (Crates.isPreview(inventory)) event.isCancelled = true
    }

    private fun onClose(event: InventoryCloseEvent) {
        (event.inventory as? Inventory)?.let(Crates::closePreview)
    }

    private fun onUseItem(event: PlayerUseItemEvent) {
        if (event.hand == PlayerHand.MAIN && Crates.redeemLegacyItem(event.player, event.itemStack)) event.isCancelled = true
    }

    private fun onHandAnimation(event: PlayerHandAnimationEvent) {
        if (event.hand == PlayerHand.MAIN && Crates.redeemLegacyItem(event.player, event.player.itemInMainHand)) event.isCancelled = true
    }

    fun init() {
        eventNode.addListener(PlayerBlockInteractEvent::class.java, ::onInteract)
        eventNode.addListener(PlayerStartDiggingEvent::class.java, ::onStartDigging)
        eventNode.addListener(PlayerBlockBreakEvent::class.java, ::onBreak)
        eventNode.addListener(InventoryPreClickEvent::class.java, ::onPreClick)
        eventNode.addListener(InventoryCloseEvent::class.java, ::onClose)
        eventNode.addListener(PlayerUseItemEvent::class.java, ::onUseItem)
        eventNode.addListener(PlayerHandAnimationEvent::class.java, ::onHandAnimation)
        ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
    }
}
