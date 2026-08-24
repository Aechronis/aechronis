package net.aechronis.vanilla.managers

import net.aechronis.utils.hasPermission
import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerGameModeChangeEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.network.packet.server.play.PlayerInfoRemovePacket
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket
import java.util.EnumSet
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
        updateVisibility(player)
        player.sendMessage(Component.text("You are now vanished (level $level).", NamedTextColor.LIGHT_PURPLE))
        refreshVisibility()
    }

    private fun unvanish(player: Player) {
        val state = vanished.remove(player.uuid) ?: return
        state.previousGameMode?.let { player.gameMode = it }
        player.isGlowing = false
        updateVisibility(player)
        player.sendMessage(Component.text("You are now visible.", NamedTextColor.LIGHT_PURPLE))
        refreshVisibility()
    }

    /**
     * Spectator mode is not automatically invisible in Minestom. Reapply the complete rule on
     * every state change so a removed vanish rule cannot leave a player hidden indefinitely.
     */
    private fun updateVisibility(
        player: Player,
        gameMode: GameMode = player.gameMode,
    ) {
        val state = vanished[player.uuid]
        when {
            gameMode == GameMode.SPECTATOR -> player.updateViewableRule { false }
            state != null -> player.updateViewableRule { viewer -> level(viewer) > state.level }
            else -> player.updateViewableRule(null)
        }
        updatePlayerListVisibility(player, gameMode, state)
    }

    private fun updatePlayerListVisibility(
        player: Player,
        gameMode: GameMode,
        state: State?,
    ) {
        val addPacket = playerInfoPacket(player, gameMode)
        val removePacket = PlayerInfoRemovePacket(player.uuid)
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { viewer ->
            if (viewer == player) return@forEach
            viewer.sendPacket(if (canView(player, viewer, gameMode, state)) addPacket else removePacket)
        }
    }

    private fun canView(
        player: Player,
        viewer: Player,
        gameMode: GameMode = player.gameMode,
        state: State? = vanished[player.uuid],
    ): Boolean = gameMode != GameMode.SPECTATOR && (state == null || level(viewer) > state.level)

    private fun playerInfoPacket(
        player: Player,
        gameMode: GameMode,
    ): PlayerInfoUpdatePacket {
        val properties =
            player.skin?.let { skin ->
                listOf(PlayerInfoUpdatePacket.Property("textures", skin.textures, skin.signature))
            } ?: emptyList()
        val entry =
            PlayerInfoUpdatePacket.Entry(
                player.uuid,
                player.username,
                properties,
                player.isListed,
                player.latency,
                gameMode,
                player.displayName,
                null,
                player.listOrder,
                player.settings.displayedSkinParts.toInt() and 0x40 != 0,
            )
        return PlayerInfoUpdatePacket(EnumSet.allOf(PlayerInfoUpdatePacket.Action::class.java), entry)
    }

    private fun refreshVisibility() {
        vanished.toMap().forEach { (uuid, _) ->
            val player =
                MinecraftServer
                    .getConnectionManager()
                    .onlinePlayers
                    .firstOrNull { it.uuid == uuid }
                    ?: return@forEach
            updateVisibility(player)
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

    private fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        // This event fires before Minestom applies the new mode, so use the requested value.
        updateVisibility(event.player, event.newGameMode)
    }

    private fun onSpawn(event: PlayerSpawnEvent) {
        val viewer = event.player
        updateVisibility(viewer)
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            if (player != viewer && !canView(player, viewer)) {
                viewer.sendPacket(PlayerInfoRemovePacket(player.uuid))
            }
        }
        refreshVisibility()
    }

    private fun onDisconnect(event: PlayerDisconnectEvent) {
        vanished.remove(event.player.uuid)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerInputEvent::class.java, ::onInput)
        Vanilla.eventNode.addListener(PlayerGameModeChangeEvent::class.java, ::onGameModeChange)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, ::onSpawn)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(::updateVisibility)
    }
}
