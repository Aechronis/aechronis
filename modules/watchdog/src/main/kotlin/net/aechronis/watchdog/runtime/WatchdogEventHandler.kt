package net.aechronis.watchdog.runtime

import net.aechronis.watchdog.alert.StaffAlert
import net.aechronis.watchdog.checks.FlagSink
import net.aechronis.watchdog.checks.MovementChecks
import net.aechronis.watchdog.checks.MovementCorrection
import net.aechronis.watchdog.checks.PacketChecks
import net.aechronis.watchdog.objects.PlayerState
import net.aechronis.watchdog.objects.PlayerStateReg
import net.aechronis.watchdog.probe.TranslationProbe
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityAttackEvent
import net.minestom.server.event.entity.EntityTeleportEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerPacketEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.network.packet.client.common.ClientPongPacket
import net.minestom.server.network.packet.client.play.ClientAnimationPacket
import net.minestom.server.network.packet.client.play.ClientInteractEntityPacket
import net.minestom.server.network.packet.client.play.ClientPlayerPositionAndRotationPacket
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket
import net.minestom.server.network.packet.client.play.ClientPlayerRotationPacket
import net.minestom.server.network.packet.client.play.ClientUpdateSignPacket

internal class WatchdogEventHandler(
    private val config: net.aechronis.watchdog.WatchdogConfig,
    private val state: (Player) -> PlayerState,
    private val isBypassed: (Player) -> Boolean,
    private val attackRecorder: AttackRecorder,
    private val probes: TranslationProbe,
    private val alerts: StaffAlert,
    private val flag: FlagSink,
) {
    fun register(eventNode: EventNode<Event>) {
        eventNode.addListener(PlayerSpawnEvent::class.java, ::onSpawn)
        eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
        eventNode.addListener(PlayerLoadedEvent::class.java, ::onLoaded)
        eventNode.addListener(PlayerPacketEvent::class.java, ::onPacket)
        eventNode.addListener(PlayerMoveEvent::class.java, ::onMove)
        eventNode.addListener(EntityAttackEvent::class.java, ::onAttack)
        eventNode.addListener(EntityTeleportEvent::class.java, ::onTeleport)
        eventNode.addListener(PlayerDeathEvent::class.java, ::onDeath)
    }

    private fun onSpawn(event: PlayerSpawnEvent) {
        val player = event.player
        probes.end(player, notify = false)
        state(player).reset(player.position)
        if (!isBypassed(player)) probes.start(player)
    }

    private fun onDisconnect(event: PlayerDisconnectEvent) {
        probes.end(event.player, notify = false)
        alerts.forget(event.player)
        PlayerStateReg.remove(event.player)
    }

    private fun onLoaded(event: PlayerLoadedEvent) {
        probes.end(event.player, notify = true)
    }

    private fun onPacket(event: PlayerPacketEvent) {
        val player = event.player
        if (isBypassed(player)) return
        val playerState = state(player)
        val packet = event.packet
        val now = System.nanoTime()

        when (packet) {
            is ClientPlayerPositionPacket -> {
                playerState.recordPacket(packet.position(), packet.onGround(), now)
                if (PacketChecks.onPacket(player, playerState, packet.position(), null, null, config, flag)) {
                    rejectPacket(event, playerState)
                }
            }

            is ClientPlayerPositionAndRotationPacket -> {
                playerState.recordPacket(
                    packet.position(),
                    packet.onGround(),
                    now,
                    packet.position().yaw,
                    packet.position().pitch,
                )
                if (
                    PacketChecks.onPacket(
                        player,
                        playerState,
                        packet.position(),
                        packet.position().yaw,
                        packet.position().pitch,
                        config,
                        flag,
                    )
                ) {
                    rejectPacket(event, playerState)
                }
            }

            is ClientPlayerRotationPacket -> {
                playerState.recordRotation(packet.yaw(), packet.pitch(), packet.onGround(), now)
                if (PacketChecks.onPacket(player, playerState, null, packet.yaw(), packet.pitch(), config, flag)) {
                    rejectPacket(event, playerState)
                }
            }

            is ClientAnimationPacket -> {
                attackRecorder.recordSwing(player)
            }

            is ClientInteractEntityPacket -> {
                val target = player.instance?.getEntityById(packet.targetId()) as? Player
                if (target != null) attackRecorder.recordAttackAttempt(player, target)
            }

            is ClientUpdateSignPacket -> {
                probes.handleResponse(event, packet)
            }

            is ClientPongPacket -> {
                playerState.pendingPings.remove(packet.id())
            }

            else -> {
                Unit
            }
        }
    }

    private fun onMove(event: PlayerMoveEvent) {
        if (event.isCancelled) return
        val player = event.player
        if (isBypassed(player)) return
        val playerState = state(player)
        if (MovementChecks.onMove(player, playerState, event.newPosition, event.isOnGround, config, flag)) {
            // Minestom sends the authoritative pre-move position when a
            // PlayerMoveEvent is cancelled.
            event.isCancelled = true
        }
    }

    private fun rejectPacket(
        event: PlayerPacketEvent,
        state: PlayerState,
    ) {
        event.isCancelled = true
        MovementCorrection.send(event.player, state)
    }

    private fun onAttack(event: EntityAttackEvent) {
        val attacker = event.entity as? Player ?: return
        val target = event.target as? Player ?: return
        attackRecorder.recordAttackAttempt(attacker, target)
    }

    private fun onTeleport(event: EntityTeleportEvent) {
        val player = event.entity as? Player ?: return
        probes.end(player, notify = false)
        state(player).reset(event.newPosition)
        state(player).movementExemptUntilTick = PlayerStateReg.currentTick + 3
    }

    private fun onDeath(event: PlayerDeathEvent) {
        probes.end(event.player, notify = false)
        state(event.player).reset(event.player.position)
    }
}
