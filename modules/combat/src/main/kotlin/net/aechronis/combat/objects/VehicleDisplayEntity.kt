package net.aechronis.combat.objects

import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket
import net.minestom.server.network.packet.server.play.EntityPositionPacket
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket
import net.minestom.server.network.packet.server.play.EntityRotationPacket
import net.minestom.server.network.packet.server.play.EntityTeleportPacket
import net.minestom.server.utils.PacketSendingUtils

/** Vehicle displays inherit the carried viewer's movement interpolation, including after respawning in view. */
internal class VehicleDisplayEntity(
    private val vehicleEntity: Entity? = null,
) : Entity(EntityType.ITEM_DISPLAY) {
    init {
        if (vehicleEntity != null) {
            requireNotNull(VehicleRegistry.runtime(vehicleEntity)).addDisplay(this)
        }
    }

    override fun updateNewViewer(player: Player) {
        super.updateNewViewer(player)
        val runtime = VehicleRegistry.runtime(vehicleEntity ?: this) ?: return
        if (runtime.isCarrying(player)) runtime.updateDisplayInterpolation(this, player)
    }

    // Chunk-wide batching bypasses sendPacketToViewers and can publish a hull update
    // separately from the carried player's movement. Route movement through this entity.
    override fun hasPredictableViewers(): Boolean = false

    override fun sendPacketToViewers(packet: SendablePacket) {
        val runtime = VehicleRegistry.runtime(vehicleEntity ?: this)
        if (
            runtime != null &&
            runtime.hasCarriedPlayers() &&
            (
                packet is EntityPositionSyncPacket ||
                    packet is EntityTeleportPacket ||
                    packet is EntityPositionPacket ||
                    packet is EntityPositionAndRotationPacket ||
                    packet is EntityRotationPacket
            )
        ) {
            runtime.queueDisplayMovement(this)
            // Observers keep normal movement packets; carried viewers get one final
            // absolute pose in the same bundle as their own relative movement.
            PacketSendingUtils.sendGroupedPacket(viewers, packet as ServerPacket) {
                !runtime.isCarrying(it)
            }
        } else {
            super.sendPacketToViewers(packet)
        }
    }

    override fun remove() {
        VehicleRegistry.runtime(vehicleEntity ?: this)?.removeDisplay(this)
        super.remove()
    }
}
