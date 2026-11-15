package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerCommandEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PlayerActivityListener {
    // Players whose join was announced; a failed login never spawns and must not announce a leave.
    private val announced = ConcurrentHashMap.newKeySet<UUID>()

    fun onPlayerConfiguration(event: AsyncPlayerConfigurationEvent) {
        event.player.gameMode = Vanilla.config.defaultGameMode
    }

    fun onSpawn(event: PlayerSpawnEvent) {
        if (!event.isFirstSpawn) return
        announced.add(event.player.uuid)
        announce("[+] ${event.player.username}")
    }

    fun onDisconnect(event: PlayerDisconnectEvent) {
        if (!announced.remove(event.player.uuid)) return
        announce("[-] ${event.player.username}")
    }

    fun onCommand(event: PlayerCommandEvent) {
        val command = event.command.removePrefix("/")
        println("[Command] ${event.player.username}: /$command")
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

    fun init() {
        // Players already online when the module (re)loads joined before this listener existed.
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { announced.add(it.uuid) }
        Vanilla.eventNode.addListener(AsyncPlayerConfigurationEvent::class.java, PlayerActivityListener::onPlayerConfiguration)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, PlayerActivityListener::onSpawn)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, PlayerActivityListener::onDisconnect)
        Vanilla.eventNode.addListener(PlayerCommandEvent::class.java, PlayerActivityListener::onCommand)
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, PlayerActivityListener::onBlockBreak)
    }
}
