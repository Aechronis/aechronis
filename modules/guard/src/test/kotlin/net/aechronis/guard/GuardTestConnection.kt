package net.aechronis.guard

import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.PlayerConnection
import java.net.InetSocketAddress
import java.net.SocketAddress

class GuardTestConnection : PlayerConnection() {
    override fun sendPacket(packet: SendablePacket) = Unit

    override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
}
