package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.utils.GunAnimation
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryOpenEvent
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.client.play.ClientAnimationPacket
import net.minestom.server.network.packet.client.play.ClientClickWindowPacket
import net.minestom.server.network.packet.client.play.ClientCreativeInventoryActionPacket
import net.minestom.server.network.packet.client.play.ClientHeldItemChangePacket
import net.minestom.server.network.packet.client.play.ClientInteractEntityPacket
import net.minestom.server.network.packet.client.play.ClientPickItemFromBlockPacket
import net.minestom.server.network.packet.client.play.ClientPickItemFromEntityPacket
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket
import net.minestom.server.network.packet.client.play.ClientUseItemPacket
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket
import net.minestom.server.network.packet.server.play.SetSlotPacket
import net.minestom.server.network.packet.server.play.WindowItemsPacket
import java.util.concurrent.ConcurrentHashMap

/** Client-only crew controls. Authoritative inventory stacks are never replaced or snapshotted. */
internal object VehicleSeatHotbar {
    private class Session(
        val originalSlot: Byte,
    ) {
        @Volatile var items: List<ItemStack> = List(9) { hiddenHand }
    }

    private val sessions = ConcurrentHashMap<Player, Session>()
    private val hiddenHand = ItemStack.of(Material.BONE).withItemModel("aechronis:invisible").withCustomName(Component.empty())

    fun isActive(player: Player): Boolean = sessions.containsKey(player)

    fun open(player: Player) {
        val ride = VehicleRegistry.ride(player) ?: return
        // Remote drones own a camera, throttle hotbar, and a separate operator mannequin.
        if (ride.vehicle.customDriverView || ride.definition.handheld) return
        if (sessions.putIfAbsent(player, Session(player.heldSlot)) == null) {
            player.closeInventory()
            Combat.reloadTasks.remove(player)?.cancel()
            Combat.placeTasks.remove(player)?.cancel()
            Combat.aimingResetTasks.remove(player)?.cancel()
            Combat.playerAiming.remove(player)
            GunAnimation.cancel(player)
            player.clearItemUse()
            player.clearTitle()
        }
        player.setHeldItemSlot(ride.seatIndex.toByte())
    }

    fun refresh(entity: Entity) {
        val rides = VehicleRegistry.ridesOf(entity)
        for (ride in rides) {
            val session = sessions[ride.player] ?: continue
            session.items =
                List(9) { index ->
                    val definition = ride.vehicle.seats.getOrNull(index) ?: return@List hiddenHand
                    val occupant = rides.firstOrNull { it.definition.id == definition.id }?.player
                    val ownSeat = occupant === ride.player
                    val color =
                        when {
                            ownSeat -> NamedTextColor.GREEN
                            occupant != null -> NamedTextColor.RED
                            else -> NamedTextColor.YELLOW
                        }
                    val state =
                        when {
                            ownSeat -> "You"
                            occupant != null -> occupant.username
                            else -> "Available"
                        }
                    ItemStack
                        .of(Material.BONE)
                        .withItemModel("aechronis:seat-${if (occupant != null && !ownSeat) "occupied" else definition.role.icon}")
                        .withCustomName(
                            Component.text("${index + 1}: ${definition.name} · $state", color).decoration(TextDecoration.ITALIC, false),
                        ).with(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, ownSeat)
                }
            sendItems(ride.player, session.items)
            ride.player.setHeldItemSlot(ride.seatIndex.toByte())
        }
    }

    fun close(player: Player) {
        val session = sessions.remove(player) ?: return
        if (!player.isOnline) {
            player.refreshHeldSlot(session.originalSlot)
            return
        }
        // Reloading may have consumed inventory ammunition while the overlay was visible.
        player.inventory.update(player)
        player.setHeldItemSlot(session.originalSlot)
    }

    private fun sendItems(
        player: Player,
        items: List<ItemStack>,
    ) {
        items.forEachIndexed { slot, item -> player.sendPacket(SetPlayerInventorySlotPacket(slot, item)) }
        player.sendPacket(SetPlayerInventorySlotPacket(40, hiddenHand))
    }

    internal fun maskPacket(
        player: Player,
        packet: ServerPacket,
    ): ServerPacket {
        val items = sessions[player]?.items ?: return packet

        fun item(
            slot: Int,
            original: ItemStack,
        ): ItemStack =
            if (slot in 0..8) {
                items[slot]
            } else if (slot == 40) {
                hiddenHand
            } else {
                original
            }
        return when (packet) {
            is SetPlayerInventorySlotPacket -> {
                val replacement = item(packet.slot, packet.itemStack)
                if (replacement == packet.itemStack) packet else SetPlayerInventorySlotPacket(packet.slot, replacement)
            }
            is SetSlotPacket -> {
                if (packet.windowId != 0) return packet
                val replacement = item(hotbarSlot(packet.slot.toInt()), packet.itemStack)
                if (replacement == packet.itemStack) packet else SetSlotPacket(packet.windowId, packet.stateId, packet.slot, replacement)
            }
            is WindowItemsPacket -> {
                if (packet.windowId != 0) return packet
                val replaced = packet.items.mapIndexed { slot, original -> item(hotbarSlot(slot), original) }
                if (replaced == packet.items) packet else WindowItemsPacket(packet.windowId, packet.stateId, replaced, packet.carriedItem)
            }
            else -> packet
        }
    }

    private fun hotbarSlot(windowSlot: Int): Int =
        when (windowSlot) {
            in 36..44 -> windowSlot - 36
            45 -> 40
            else -> -1
        }

    internal fun onPacket(event: PlayerPacketEvent) {
        val player = event.player
        if (event.isCancelled || !isActive(player)) return
        when (val packet = event.packet) {
            is ClientHeldItemChangePacket -> {
                // Intercept before handheld-item listeners or the normal held-slot update.
                event.isCancelled = true
                val ride = VehicleRegistry.ride(player) ?: return
                if (packet.slot.toInt() in 0..8) ride.vehicle.switchSeat(player, packet.slot.toInt())
                VehicleRegistry.ride(player)?.let { player.setHeldItemSlot(it.seatIndex.toByte()) }
            }
            is ClientClickWindowPacket, is ClientCreativeInventoryActionPacket,
            is ClientPickItemFromBlockPacket, is ClientPickItemFromEntityPacket,
            -> {
                event.isCancelled = true
                player.inventory.update(player)
            }
            is ClientPlayerActionPacket -> {
                event.isCancelled = true
                if (packet.status in
                    setOf(ClientPlayerActionPacket.Status.STARTED_DIGGING, ClientPlayerActionPacket.Status.FINISHED_DIGGING)
                ) {
                    player.sendPacket(BlockChangePacket(packet.blockPosition, player.instance.getBlock(packet.blockPosition)))
                }
                player.sendPacket(AcknowledgeBlockChangePacket(packet.sequence))
                sessions[player]?.let { sendItems(player, it.items) }
            }
            is ClientPlayerBlockPlacementPacket -> {
                event.isCancelled = true
                player.sendPacket(AcknowledgeBlockChangePacket(packet.sequence))
            }
            is ClientUseItemPacket -> {
                event.isCancelled = true
                player.sendPacket(AcknowledgeBlockChangePacket(packet.sequence))
            }
            is ClientAnimationPacket, is ClientInteractEntityPacket -> event.isCancelled = true
            else -> Unit
        }
    }

    fun initListeners() {
        Combat.highPriorityEventNode.addListener(PlayerPacketEvent::class.java, ::onPacket)
        Combat.eventNode.addListener(InventoryOpenEvent::class.java) { event ->
            if (isActive(event.player)) event.isCancelled = true
        }
        Combat.eventNode.addListener(PlayerPacketOutEvent::class.java) { event ->
            if (event.isCancelled) return@addListener
            val replacement = maskPacket(event.player, event.packet)
            if (replacement !== event.packet) {
                event.isCancelled = true
                event.player.sendPacket(replacement)
            }
        }
    }

    fun shutdown() = sessions.keys.toList().forEach(::close)
}
