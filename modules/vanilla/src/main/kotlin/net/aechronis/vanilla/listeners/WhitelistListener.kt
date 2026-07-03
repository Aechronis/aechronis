package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Whitelist
import net.kyori.adventure.text.Component
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent

object WhitelistListener {
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (!Whitelist.enabled) return
        if (Whitelist.isWhitelistedName(event.gameProfile.name())) return

        event.connection.kick(Component.text("You are not whitelisted on this server"))
    }

    fun init() {
        Vanilla.eventNode.addListener(AsyncPlayerPreLoginEvent::class.java, WhitelistListener::onPreLogin)
    }
}
