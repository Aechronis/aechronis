package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.tasks.ModelManager
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket
import net.minestom.server.network.packet.server.play.SetSlotPacket
import net.minestom.server.network.packet.server.play.WindowItemsPacket
import net.minestom.server.utils.inventory.PlayerInventoryUtils
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/** A private, world-oriented optical housing. The normal player camera keeps free mouse look. */
internal object TurretScope {
    // The client seats an avatar 0.6 blocks below a zero-height item display.
    const val DEFAULT_EYE_HEIGHT = 1.62 - 0.6

    private class ScopeDisplay : Entity(EntityType.ITEM_DISPLAY) {
        // Passenger movement must not copy the player's view into this entity.
        // The optical transform is its only rotation; the seat supplies position.
        override fun setPositionInternal(
            newPosition: Pos,
            headRotation: Float,
        ) {
            super.setPositionInternal(newPosition.withView(0f, 0f), 0f)
        }

        override fun synchronizePosition() {
            if (vehicle == null) super.synchronizePosition()
        }

        // Even a redundant zero-rotation packet makes the client snap a display
        // with zero position interpolation, resetting its previous position.
        // That breaks its frame-by-frame alignment with the passenger camera.
        override fun synchronizeView() = Unit
    }

    private data class Session(
        val ride: VehicleRide,
        val display: Entity,
        val weaponModel: String,
    )

    private val sessions = ConcurrentHashMap<Player, Session>()
    private val hiddenHand = ItemStack.of(Material.SCULK_VEIN).withItemModel("aechronis:invisible").withCustomName(Component.empty())
    private val handSlots = (0..8).toList() + PlayerInventoryUtils.OFFHAND_SLOT

    fun isActive(player: Player): Boolean = sessions.containsKey(player)

    fun eyeHeight(player: Player): Double = (player.eyeHeight - 0.6) * player.getAttribute(Attribute.SCALE).value

    fun open(
        player: Player,
        ride: VehicleRide,
        weaponModel: String,
    ) {
        if (isActive(player)) return
        val instance = player.instance ?: return
        val display = ScopeDisplay()
        display.isAutoViewable = false
        display.updateViewableRule { it === player }
        val meta = display.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.of(Material.BONE).withItemModel("aechronis:turret-scope")
        meta.isHasNoGravity = true
        meta.posRotInterpolationDuration = 0
        meta.transformationInterpolationDuration = 3
        meta.translation = Vec(0.0, eyeHeight(player), 0.0)
        meta.width = 0f
        meta.height = 0f
        meta.shadowRadius = 0f
        meta.shadowStrength = 0f
        meta.setBrightness(15, 15)
        sessions[player] = Session(ride, display, weaponModel)
        try {
            display.setInstance(instance, ride.seat.position.withView(0f, 0f))
            display.addViewer(player)
            // Siblings share the seat's interpolated translation, preventing the mask
            // from trailing the eye while a fast hull moves. Rotation is independent.
            ride.seat.addPassenger(display)
            ModelManager.updateModel(player)
            sendHandItems(player, masked = true)
        } catch (failure: Exception) {
            close(player)
            throw failure
        }
    }

    fun update(
        player: Player,
        bore: Pos,
    ): Boolean {
        val session = sessions[player] ?: return false
        val display = session.display
        if (display.isRemoved || display.instance !== player.instance || display.vehicle !== session.ride.seat) return false
        val meta = display.entityMeta as ItemDisplayMeta
        val yaw = Math.toRadians(-bore.yaw.toDouble()) / 2.0
        val pitch = Math.toRadians(bore.pitch.toDouble()) / 2.0
        // RY(-yaw) * RX(pitch), matching Minecraft's view direction. ItemDisplay's
        // built-in 180-degree turn makes the model's -Z aperture face world +Z.
        val rotation =
            floatArrayOf(
                (cos(yaw) * sin(pitch)).toFloat(),
                (sin(yaw) * cos(pitch)).toFloat(),
                (-sin(yaw) * sin(pitch)).toFloat(),
                (cos(yaw) * cos(pitch)).toFloat(),
            )
        val translation = Vec(0.0, eyeHeight(player), 0.0)
        if (meta.leftRotation.contentEquals(rotation) && meta.translation == translation) return true
        meta.setNotifyAboutChanges(false)
        try {
            meta.translation = translation
            meta.leftRotation = rotation
            // Smooth the sight over several server ticks. The start marker
            // must travel with the new transform on every change.
            meta.transformationInterpolationStartDelta = 0
        } finally {
            meta.setNotifyAboutChanges(true)
        }
        return true
    }

    fun close(player: Player) {
        val session = sessions.remove(player) ?: return
        try {
            if (!session.display.isRemoved) session.display.remove()
        } finally {
            session.ride.runtime.setPartHidden(player, session.weaponModel, false)
            // Restore current authoritative stacks, including ammunition used while scoped.
            sendHandItems(player, masked = false)
            player.instance?.let { ModelManager.syncShaderTime(player, it.worldAge) }
        }
    }

    private fun sendHandItems(
        player: Player,
        masked: Boolean,
    ) {
        handSlots.forEach { slot ->
            player.sendPacket(
                SetPlayerInventorySlotPacket(
                    PlayerInventoryUtils.convertMinestomSlotToPlayerInventorySlot(slot),
                    if (masked) hiddenHand else player.inventory.getItemStack(slot),
                ),
            )
        }
    }

    /** Keep inventory refreshes and handheld gun actions from redrawing hands over the sight. */
    internal fun maskHandPacket(packet: ServerPacket): ServerPacket =
        when (packet) {
            is SetPlayerInventorySlotPacket -> {
                if ((packet.slot in 0..8 || packet.slot == 40) && packet.itemStack != hiddenHand) {
                    SetPlayerInventorySlotPacket(packet.slot, hiddenHand)
                } else {
                    packet
                }
            }
            is SetSlotPacket -> {
                if (packet.windowId == 0 && packet.slot.toInt() in 36..45 && packet.itemStack != hiddenHand) {
                    SetSlotPacket(packet.windowId, packet.stateId, packet.slot, hiddenHand)
                } else {
                    packet
                }
            }
            is WindowItemsPacket -> {
                if (packet.windowId == 0 && packet.items.indices.any { it in 36..45 && packet.items[it] != hiddenHand }) {
                    WindowItemsPacket(
                        packet.windowId,
                        packet.stateId,
                        packet.items.mapIndexed { slot, item ->
                            if (slot in 36..45) hiddenHand else item
                        },
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
            if (event.isCancelled || !isActive(event.player)) return@addListener
            val replacement = maskHandPacket(event.packet)
            if (replacement === event.packet) return@addListener
            event.isCancelled = true
            event.player.sendPacket(replacement)
        }
    }

    fun shutdown() = sessions.keys.toList().forEach(::close)
}
