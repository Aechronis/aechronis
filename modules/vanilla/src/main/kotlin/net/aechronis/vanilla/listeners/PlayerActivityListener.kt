package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerCommandEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent

object PlayerActivityListener {
    fun onSpawn(event: PlayerSpawnEvent) {
        if (!event.isFirstSpawn) return
        announce("[+] ${event.player.username}")
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        announce("[-] ${event.player.username}")
    }

    fun onCommand(event: PlayerCommandEvent) {
        val command = event.command.removePrefix("/")
        val name = command.substringBefore(' ').lowercase()
        val logged = if (name in SENSITIVE_COMMANDS) "$name [redacted]" else command
        println("[Command] ${event.player.username}: /$logged")
    }

    fun onBlockBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return
        val blockCenter = event.blockPosition.asVec().add(0.5, 0.5, 0.5)
        if (event.player.position.distanceSquared(blockCenter) > 36.0) event.isCancelled = true
    }

    private fun announce(message: String) {
        println(message)
        val component = Component.text(message, NamedTextColor.DARK_GRAY)
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.sendMessage(component) }
    }

    private val SENSITIVE_COMMANDS = setOf("login", "register", "password", "changepassword")

    fun init() {
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, PlayerActivityListener::onSpawn)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, PlayerActivityListener::onDisconnect)
        Vanilla.eventNode.addListener(PlayerCommandEvent::class.java, PlayerActivityListener::onCommand)
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, PlayerActivityListener::onBlockBreak)
    }
}
