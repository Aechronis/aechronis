package net.aechronis.vanilla.managers

import net.aechronis.utils.hasPermission
import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerInputEvent
import java.util.UUID

object Vanish {
    private const val DOUBLE_SHIFT_WINDOW_MILLIS = 500L
    private val vanished = mutableMapOf<UUID, State>()

    private data class State(
        val level: Int,
        var previousGameMode: GameMode? = null,
        var lastShiftPress: Long = 0,
    )

    fun level(player: Player): Int = (5 downTo 1).firstOrNull { player.hasPermission("vanilla.vanish.$it") } ?: 0

    fun isVanished(player: Player): Boolean = player.uuid in vanished

    fun toggle(player: Player) {
        if (isVanished(player)) {
            unvanish(player)
        } else {
            vanish(player, level(player))
        }
    }

    private fun vanish(
        player: Player,
        level: Int,
    ) {
        require(level > 0) { "A vanish level is required" }
        vanished[player.uuid] = State(level)
        player.isGlowing = true
        player.updateViewableRule { viewer -> level(viewer) > level }
        player.sendMessage(Component.text("You are now vanished (level $level).", NamedTextColor.LIGHT_PURPLE))
        refreshVisibility()
    }

    private fun unvanish(player: Player) {
        val state = vanished.remove(player.uuid) ?: return
        state.previousGameMode?.let { player.gameMode = it }
        player.isGlowing = false
        player.updateViewableRule(null)
        player.sendMessage(Component.text("You are now visible.", NamedTextColor.LIGHT_PURPLE))
        refreshVisibility()
    }

    private fun refreshVisibility() {
        vanished.toMap().forEach { (uuid, state) ->
            val player =
                MinecraftServer
                    .getConnectionManager()
                    .onlinePlayers
                    .firstOrNull { it.uuid == uuid }
                    ?: return@forEach
            player.updateViewableRule { viewer -> level(viewer) > state.level }
        }
    }

    private fun onInput(event: PlayerInputEvent) {
        if (!event.hasPressedShiftKey()) return
        val player = event.player
        val state = vanished[player.uuid] ?: return
        val now = System.currentTimeMillis()
        if (now - state.lastShiftPress > DOUBLE_SHIFT_WINDOW_MILLIS) {
            state.lastShiftPress = now
            return
        }

        state.lastShiftPress = 0
        val previousGameMode = state.previousGameMode
        if (previousGameMode == null) {
            if (player.gameMode == GameMode.SPECTATOR) return
            state.previousGameMode = player.gameMode
            player.gameMode = GameMode.SPECTATOR
            player.sendMessage(Component.text("Spectator mode enabled.", NamedTextColor.LIGHT_PURPLE))
        } else {
            player.gameMode = previousGameMode
            state.previousGameMode = null
            player.sendMessage(Component.text("Restored $previousGameMode mode.", NamedTextColor.LIGHT_PURPLE))
        }
    }

    private fun onDisconnect(event: PlayerDisconnectEvent) {
        vanished.remove(event.player.uuid)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerInputEvent::class.java, ::onInput)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
    }
}
