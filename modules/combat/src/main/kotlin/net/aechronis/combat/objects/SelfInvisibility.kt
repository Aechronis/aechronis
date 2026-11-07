package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Metadata
import net.minestom.server.entity.MetadataDef
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket
import net.minestom.server.network.packet.server.play.SetSlotPacket
import net.minestom.server.network.packet.server.play.WindowItemsPacket
import net.minestom.server.utils.inventory.PlayerInventoryUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * F5 still renders the local avatar. Hides a player's own skin and armor from their own
 * client only; other viewers are unaffected. Each owner keeps the player hidden until it shows them.
 */
internal object SelfInvisibility {
    private val owners = ConcurrentHashMap<Player, Set<String>>()
    private val armorPlayerSlots =
        EquipmentSlot.armors().map {
            PlayerInventoryUtils.convertMinestomSlotToPlayerInventorySlot(
                it.armorSlot(),
            )
        }
    private val armorWindowSlots = EquipmentSlot.armors().map { PlayerInventoryUtils.convertMinestomSlotToWindowSlot(it.armorSlot()) }

    fun isHidden(player: Player): Boolean = owners.containsKey(player)

    fun hide(
        player: Player,
        owner: String,
    ) {
        var hidden = false
        owners.compute(player) { _, current ->
            hidden = current == null
            current.orEmpty() + owner
        }
        if (hidden) sendSelfAppearance(player)
    }

    fun show(
        player: Player,
        owner: String,
    ) {
        var shown = false
        owners.computeIfPresent(player) { _, current ->
            (current - owner).ifEmpty {
                shown = true
                null
            }
        }
        if (shown) sendSelfAppearance(player)
    }

    /** Resends the current flags and armor; the outgoing listener masks them while hidden. */
    private fun sendSelfAppearance(player: Player) {
        val index = MetadataDef.ENTITY_FLAGS.index()
        player.sendPacket(
            EntityMetaDataPacket(
                player.entityId,
                mapOf(index to (player.metadataPacket.entries[index] ?: Metadata.Byte(0))),
            ),
        )
        player.sendPacket(EntityEquipmentPacket(player.entityId, EquipmentSlot.armors().associateWith(player::getEquipment)))
    }

    internal fun maskSelfPacket(
        player: Player,
        packet: ServerPacket,
    ): ServerPacket =
        when (packet) {
            is EntityMetaDataPacket -> {
                val index = MetadataDef.ENTITY_FLAGS.index()
                val flags = packet.entries[index]?.value() as? Byte
                if (packet.entityId != player.entityId || flags == null) {
                    packet
                } else {
                    // Invisibility suppresses the skin; clearing glow also suppresses its outline.
                    val hidden = ((flags.toInt() or 0x20) and 0x40.inv()).toByte()
                    if (hidden ==
                        flags
                    ) {
                        packet
                    } else {
                        EntityMetaDataPacket(packet.entityId, packet.entries + (index to Metadata.Byte(hidden)))
                    }
                }
            }
            is EntityEquipmentPacket -> {
                if (packet.entityId != player.entityId || packet.equipments.none { it.key.isArmor && !it.value.isAir }) {
                    packet
                } else {
                    EntityEquipmentPacket(
                        packet.entityId,
                        packet.equipments.mapValues { (slot, item) ->
                            if (slot.isArmor) ItemStack.AIR else item
                        },
                    )
                }
            }
            is SetPlayerInventorySlotPacket -> {
                if (packet.slot in armorPlayerSlots &&
                    !packet.itemStack.isAir
                ) {
                    SetPlayerInventorySlotPacket(packet.slot, ItemStack.AIR)
                } else {
                    packet
                }
            }
            is SetSlotPacket -> {
                if (packet.windowId == 0 && packet.slot.toInt() in armorWindowSlots && !packet.itemStack.isAir) {
                    SetSlotPacket(packet.windowId, packet.stateId, packet.slot, ItemStack.AIR)
                } else {
                    packet
                }
            }
            is WindowItemsPacket -> {
                if (packet.windowId == 0 && armorWindowSlots.any { packet.items.getOrNull(it)?.isAir == false }) {
                    WindowItemsPacket(
                        packet.windowId,
                        packet.stateId,
                        packet.items.mapIndexed { slot, item -> if (slot in armorWindowSlots) ItemStack.AIR else item },
                        packet.carriedItem,
                    )
                } else {
                    packet
                }
            }
            else -> packet
        }

    fun initListeners() {
        Combat.eventNode.addListener(PlayerPacketOutEvent::class.java) { event ->
            if (event.isCancelled || !isHidden(event.player)) return@addListener
            val replacement = maskSelfPacket(event.player, event.packet)
            if (replacement === event.packet) return@addListener
            event.isCancelled = true
            event.player.sendPacket(replacement)
        }
    }
}
