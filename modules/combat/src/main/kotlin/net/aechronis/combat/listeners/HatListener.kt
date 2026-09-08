package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Hat
import net.aechronis.combat.objects.HatMenu
import net.aechronis.combat.storage.HatCollection
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.CreativeInventoryActionEvent
import net.minestom.server.event.inventory.InventoryItemChangeEvent
import net.minestom.server.event.inventory.InventoryOpenEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.item.EntityEquipEvent
import net.minestom.server.event.item.PickupItemEvent
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.inventory.PlayerInventory
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket
import net.minestom.server.network.packet.server.play.SetSlotPacket
import net.minestom.server.network.packet.server.play.WindowItemsPacket
import net.minestom.server.utils.inventory.PlayerInventoryUtils

object HatListener {
    @Volatile private var active = false

    fun init() {
        active = true
        HatMenu.init()
        Combat.eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            cleanPlayer(event.player)
            refreshNextTick(event.player)
        }
        Combat.eventNode.addListener(PlayerPacketOutEvent::class.java, ::onPacketOut)
        Combat.eventNode.addListener(EntityEquipEvent::class.java) { event ->
            if (event.slot == EquipmentSlot.HELMET) (event.entity as? Player)?.let(::refreshNextTick)
        }
        Combat.eventNode.addListener(InventoryItemChangeEvent::class.java) { event ->
            if (event.inventory is PlayerInventory && Hat.isCosmeticItem(event.newItem)) {
                event.inventory.setItemStack(event.slot, ItemStack.AIR)
            }
        }
        Combat.eventNode.addListener(InventoryOpenEvent::class.java) { event ->
            cleanPlayer(event.player)
            refreshNextTick(event.player)
        }
        Combat.eventNode.addListener(InventoryPreClickEvent::class.java) { event ->
            if (event.inventory !== event.player.inventory || HatCollection.equipped(event.player.uuid) == null) return@addListener
            val touchesHelmet =
                when (val click = event.click) {
                    is Click.Drag -> PlayerInventoryUtils.HELMET_SLOT in click.slots()
                    else -> event.slot == PlayerInventoryUtils.HELMET_SLOT
                }
            if (touchesHelmet) event.isCancelled = true
        }
        Combat.eventNode.addListener(CreativeInventoryActionEvent::class.java) { event ->
            if (Hat.isCosmeticItem(event.clickedItem) ||
                (event.slot == PlayerInventoryUtils.HELMET_SLOT && HatCollection.equipped(event.player.uuid) != null)
            ) {
                event.isCancelled = true
            }
        }
        Combat.eventNode.addListener(PickupItemEvent::class.java) { event ->
            if (Hat.isCosmeticItem(event.itemEntity.itemStack)) {
                event.isCancelled = true
                event.itemEntity.remove()
            }
        }
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            cleanPlayer(player)
            refreshNextTick(player)
        }
    }

    private fun cleanPlayer(player: Player) {
        for (slot in 0 until player.inventory.size) {
            if (Hat.isCosmeticItem(player.inventory.getItemStack(slot))) player.inventory.setItemStack(slot, ItemStack.AIR)
        }
        if (Hat.isCosmeticItem(player.inventory.cursorItem)) player.inventory.cursorItem = ItemStack.AIR
    }

    private fun refreshNextTick(player: Player) {
        ModuleScheduler.scheduleNextTick {
            if (active && player.isOnline) refresh(player)
        }
    }

    fun refresh(player: Player) {
        val packet = helmetPacket(player)
        player.sendPacketToViewers(packet)
        player.sendPacket(packet)
    }

    private fun helmetPacket(player: Player): EntityEquipmentPacket {
        val visual = if (active) HatCollection.equipped(player.uuid)?.appearance ?: player.helmet else player.helmet
        return EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.HELMET to visual))
    }

    private fun onPacketOut(event: PlayerPacketOutEvent) {
        if (!active) return
        // Keep the visual helmet in every inventory resync, including rejected clicks.
        // Sending the real slot first would make the cosmetic disappear until the next tick.
        HatCollection.equipped(event.player.uuid)?.let { hat ->
            val replacement = cosmeticInventoryPacket(event.packet, hat.appearance)
            if (replacement !== event.packet) {
                event.isCancelled = true
                event.player.sendPacket(replacement)
                return
            }
        }
        val packet = event.packet as? EntityEquipmentPacket ?: return
        val helmet = packet.equipments[EquipmentSlot.HELMET] ?: return
        val target =
            if (packet.entityId == event.player.entityId) {
                event.player
            } else {
                event.player.instance?.getEntityById(packet.entityId) as? Player ?: return
            }
        val cosmetic = HatCollection.equipped(target.uuid)?.appearance
        var visual = cosmetic ?: if (helmet.hasTag(Hat.cosmeticTag)) target.helmet else return
        // Scope packets are sent only to the wearer. Preserve their camera overlay.
        if (target === event.player) {
            val overlay = helmet.get(DataComponents.EQUIPPABLE)?.cameraOverlay
            if (overlay != null) {
                val equippable =
                    visual.get(DataComponents.EQUIPPABLE)
                        ?: requireNotNull(ItemStack.of(Material.CARVED_PUMPKIN).get(DataComponents.EQUIPPABLE))
                visual = visual.with(DataComponents.EQUIPPABLE, equippable.withCameraOverlay(overlay))
            }
        }
        // Equality also prevents our replacement packet being rewritten again.
        if (visual == helmet) return
        event.isCancelled = true
        event.player.sendPacket(EntityEquipmentPacket(packet.entityId, packet.equipments + (EquipmentSlot.HELMET to visual)))
    }

    private fun cosmeticInventoryPacket(
        packet: ServerPacket,
        appearance: ItemStack,
    ): ServerPacket {
        val windowSlot = PlayerInventoryUtils.convertMinestomSlotToWindowSlot(PlayerInventoryUtils.HELMET_SLOT)
        val playerSlot = PlayerInventoryUtils.convertMinestomSlotToPlayerInventorySlot(PlayerInventoryUtils.HELMET_SLOT)
        return when (packet) {
            is WindowItemsPacket -> {
                if (packet.windowId != 0 ||
                    packet.items.getOrNull(windowSlot) == null ||
                    packet.items[windowSlot] == appearance
                ) {
                    return packet
                }
                val items = packet.items.toMutableList()
                items[windowSlot] = appearance
                WindowItemsPacket(packet.windowId, packet.stateId, items, packet.carriedItem)
            }
            is SetPlayerInventorySlotPacket -> {
                if (packet.slot != playerSlot || packet.itemStack == appearance) return packet
                SetPlayerInventorySlotPacket(packet.slot, appearance)
            }
            is SetSlotPacket -> {
                if (packet.windowId != 0 || packet.slot.toInt() != windowSlot || packet.itemStack == appearance) return packet
                SetSlotPacket(packet.windowId, packet.stateId, packet.slot, appearance)
            }
            else -> packet
        }
    }

    fun shutdown() {
        active = false
        HatMenu.shutdown()
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(::refresh)
    }
}
