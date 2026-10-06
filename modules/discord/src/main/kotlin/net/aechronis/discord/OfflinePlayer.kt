package net.aechronis.discord

import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.inventory.PlayerInventory
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import java.net.InetSocketAddress
import java.util.UUID

/** Identity adapter only: never joins a world or the connection manager. */
internal class OfflinePlayer(
    uuid: UUID,
    name: String,
    private val receive: (Component) -> Unit,
) : Player(DisconnectedConnection(), GameProfile(uuid, name)) {
    override fun sendMessage(message: Component) = receive(message)

    override fun getPosition(): Pos = error("Offline commands cannot access a player position")

    override fun getInstance(): Instance = error("Offline commands cannot access a player world")

    override fun getInventory(): PlayerInventory = error("Offline commands cannot access an inventory")

    private class DisconnectedConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = error("Offline commands cannot send game packets")

        override fun getRemoteAddress() = InetSocketAddress(0)

        override fun isOnline() = false
    }
}
