package io.github.openminigameserver.worldedit.platform.misc

import com.sk89q.worldedit.session.SessionKey
import net.minestom.server.MinecraftServer
import java.util.UUID

class SessionKeyImpl(
    private val uuid: UUID,
    private val name: String,
) : SessionKey {
    override fun getUniqueId(): UUID = uuid

    override fun getName(): String = name

    override fun isActive(): Boolean = MinecraftServer.getConnectionManager().onlinePlayers.any { it.uuid == uniqueId }

    override fun isPersistent(): Boolean = true
}
