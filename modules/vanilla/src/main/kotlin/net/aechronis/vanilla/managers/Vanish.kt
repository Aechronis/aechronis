package net.aechronis.vanilla.managers

import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.utils.VisibilityRules
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

object Vanish {
    private const val DOUBLE_SHIFT_WINDOW_MILLIS = 500L
    private const val VISIBILITY_RULE_OWNER = "vanilla:vanish"
    private val vanished = mutableMapOf<UUID, State>()

    private data class State(
        val level: Int,
        var previousGameMode: GameMode? = null,
        var lastShiftPress: Long = 0,
    )

    fun level(player: Player): Int = (5 downTo 1).firstOrNull { player.hasPermission("vanilla.vanish.$it") } ?: 0

    fun isVanished(player: Player): Boolean = player.uuid in vanished

    internal fun captureTransientState(): ByteArray {
        val states = vanished.entries.sortedBy { (uuid, _) -> uuid.toString() }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(TRANSIENT_STATE_VERSION)
                output.writeInt(states.size)
                states.forEach { (uuid, state) ->
                    output.writeLong(uuid.mostSignificantBits)
                    output.writeLong(uuid.leastSignificantBits)
                    output.writeInt(state.level)
                    output.writeInt(state.previousGameMode?.ordinal ?: NO_GAME_MODE)
                }
            }
            bytes.toByteArray()
        }
    }

    internal fun restoreTransientState(
        payload: ByteArray?,
        onlinePlayers: Collection<Player> = MinecraftServer.getConnectionManager().onlinePlayers,
    ) {
        if (payload == null) return
        val restored = decodeTransientState(payload)
        val playersById = onlinePlayers.associateBy { it.uuid }

        vanished.clear()
        restored.forEach { (uuid, saved) ->
            val player = playersById[uuid] ?: return@forEach
            val state = State(saved.level, saved.previousGameMode)
            vanished[uuid] = state
            if (saved.previousGameMode != null) player.gameMode = GameMode.SPECTATOR
            player.isGlowing = true
            updateVisibility(player)
        }
        refreshVisibility()
    }

    fun toggle(player: Player) {
        if (isVanished(player)) {
            unvanish(player)
            return
        }

        val level = level(player)
        if (level == 0) {
            player.sendMessage(Component.text("You don't have permission to vanish.", NamedTextColor.RED))
            return
        }
        vanish(player, level)
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

    private fun updateVisibility(
        player: Player,
        gameMode: GameMode = player.gameMode,
    ) {
        val state = vanished[player.uuid]
        when {
            gameMode == GameMode.SPECTATOR -> VisibilityRules.set(player, VISIBILITY_RULE_OWNER) { false }
            state != null -> VisibilityRules.set(player, VISIBILITY_RULE_OWNER) { viewer -> level(viewer) > state.level }
            else -> VisibilityRules.remove(player, VISIBILITY_RULE_OWNER)
        }
    }

    private fun refreshVisibility() {
        MinecraftServer
            .getConnectionManager()
            .onlinePlayers
            .filter { isVanished(it) || it.gameMode == GameMode.SPECTATOR }
            .forEach(::updateVisibility)
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
        // This event fires before Minestom applies the new mode. Apply the requested state now,
        // then reconcile next tick in case another listener cancelled or rewrote the event.
        updateVisibility(event.player, event.newGameMode)
        ModuleScheduler.scheduleNextTick {
            if (event.player.isOnline) updateVisibility(event.player)
        }
    }

    private fun onSpawn(event: PlayerSpawnEvent) {
        updateVisibility(event.player)
        // Joining clients initially receive every profile from Minestom. Reapply managed rules to
        // remove profiles they cannot view and add authorized profiles before their entity spawns.
        refreshVisibility()
    }

    private fun onDisconnect(event: PlayerDisconnectEvent) {
        vanished.remove(event.player.uuid)
        VisibilityRules.clear(event.player)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerInputEvent::class.java, ::onInput)
        Vanilla.eventNode.addListener(PlayerGameModeChangeEvent::class.java, ::onGameModeChange)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, ::onSpawn)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(::updateVisibility)
    }

    fun shutdown() {
        val players = MinecraftServer.getConnectionManager().onlinePlayers.associateBy { it.uuid }
        vanished.toMap().forEach { (uuid, state) ->
            players[uuid]?.let { player ->
                state.previousGameMode?.let { player.gameMode = it }
                player.isGlowing = false
                VisibilityRules.remove(player, VISIBILITY_RULE_OWNER)
            }
        }
        vanished.clear()
    }

    private fun decodeTransientState(payload: ByteArray): Map<UUID, SavedState> =
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == TRANSIENT_STATE_VERSION) { "Unsupported vanish transient-state version" }
            val size = input.readInt()
            require(size in 0..MAX_TRANSIENT_ENTRIES) { "Invalid vanish transient-state size: $size" }
            buildMap(size) {
                repeat(size) {
                    val uuid = UUID(input.readLong(), input.readLong())
                    val level = input.readInt()
                    require(level in 1..MAX_VANISH_LEVEL) { "Invalid vanish level for $uuid: $level" }
                    val gameModeId = input.readInt()
                    val previousGameMode =
                        if (gameModeId == NO_GAME_MODE) {
                            null
                        } else {
                            require(gameModeId in GameMode.entries.indices) { "Invalid previous game mode for $uuid" }
                            GameMode.entries[gameModeId].also { gameMode ->
                                require(gameMode != GameMode.SPECTATOR) { "Invalid spectator restore mode for $uuid" }
                            }
                        }
                    require(put(uuid, SavedState(level, previousGameMode)) == null) {
                        "Duplicate vanish transient-state entry for $uuid"
                    }
                }
                require(input.available() == 0) { "Trailing vanish transient-state data" }
            }
        }

    private data class SavedState(
        val level: Int,
        val previousGameMode: GameMode?,
    )

    private const val TRANSIENT_STATE_VERSION = 1
    private const val MAX_TRANSIENT_ENTRIES = 10_000
    private const val MAX_VANISH_LEVEL = 5
    private const val NO_GAME_MODE = -1
}
