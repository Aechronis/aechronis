package io.github.openminigameserver.worldedit.platform.misc

import com.sk89q.worldedit.session.SessionKey
import net.minestom.server.MinecraftServer
import java.util.*

class SessionKeyImpl(
    private val uuid: UUID, private val name: String
) : SessionKey {
    override fun getUniqueId(): UUID {
        return uuid
    }

    override fun getName(): String {
        return name
    }

    override fun isActive(): Boolean {
        return MinecraftServer.getConnectionManager().onlinePlayers.any { it.uuid == uniqueId }
    }

    override fun isPersistent(): Boolean {
        return true
    }
}
