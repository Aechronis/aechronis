package net.aechronis.watchdog.checks

import net.aechronis.watchdog.objects.PlayerState
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.entity.RelativeFlags
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket

internal object MovementCorrection {
    fun send(
        player: Player,
        state: PlayerState,
    ) {
        val position = state.lastAcceptedPosition ?: player.position
        player.sendPacket(
            PlayerPositionAndLookPacket(
                player.nextTeleportId,
                position,
                Vec.ZERO,
                position.yaw,
                position.pitch,
                RelativeFlags.NONE,
            ),
        )
    }
}
