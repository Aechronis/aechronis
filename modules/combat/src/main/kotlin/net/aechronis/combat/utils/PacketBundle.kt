package net.aechronis.combat.utils

import net.aechronis.server.network.BufferedPacketEventConnection
import net.minestom.server.MinecraftServer
import net.minestom.server.ServerFlag
import net.minestom.server.adventure.MinestomAdventure
import net.minestom.server.entity.Player
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.player.PlayerPacketOutEvent
import net.minestom.server.network.ConnectionState
import net.minestom.server.network.NetworkBuffer
import net.minestom.server.network.packet.PacketWriting
import net.minestom.server.network.packet.server.BufferedPacket
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.BundlePacket

/**
 * Minestom 26.2 has delimiter packets but no atomic collection enqueue. Its
 * BufferedPacket explicitly supports multiple framed packets in one queue entry;
 * the socket writer still applies connection encryption to that entire entry.
 *
 * The Via transport dispatches the event and translation sequence on its writer
 * while retaining the atomic bundle, including while Grim is disabled.
 * Otherwise perform that sequence here before framing. No delimiter is published
 * around callbacks or inventory changes.
 * Requires an already-PLAY connection accepting framed BufferedPackets.
 * ConnectionManager enables the configured compression threshold before login
 * enters configuration; the Via transport also supports this framing.
 */
internal fun preparePacketBundle(
    player: Player,
    packets: List<ServerPacket>,
    compressionThreshold: Int = MinecraftServer.getCompressionThreshold(),
): BufferedPacket {
    require(packets.size <= 4096) { "Bundle exceeds the client packet limit" }
    require(packets.none { it is BundlePacket }) { "Bundles cannot contain delimiters" }
    val deferredEvents = player.playerConnection is BufferedPacketEventConnection
    val outgoing = EventDispatcher.getHandle(PlayerPacketOutEvent::class.java)
    val translated =
        packets.mapNotNull { packet ->
            if (!deferredEvents && outgoing.hasListener()) {
                val event = PlayerPacketOutEvent(player, packet)
                outgoing.call(event)
                if (event.isCancelled) return@mapNotNull null
            }
            if (!deferredEvents && ServerFlag.AUTOMATIC_COMPONENT_TRANSLATION && packet is ServerPacket.ComponentHolding) {
                packet.copyWithOperator { component ->
                    MinestomAdventure.COMPONENT_TRANSLATOR.apply(component, player.locale ?: MinestomAdventure.getDefaultLocale())
                }
            } else {
                packet
            }
        }
    val buffer = NetworkBuffer.resizableBuffer(MinecraftServer.getRegistries())
    val delimiter = BundlePacket()
    for (packet in listOf(delimiter) + translated + delimiter) {
        PacketWriting.writeFramedPacket(buffer, ConnectionState.PLAY, packet, compressionThreshold)
        require(buffer.writeIndex() < ServerFlag.MAX_PACKET_SIZE) { "Bundle exceeds the socket buffer limit" }
    }
    return BufferedPacket(buffer, 0, buffer.writeIndex())
}
