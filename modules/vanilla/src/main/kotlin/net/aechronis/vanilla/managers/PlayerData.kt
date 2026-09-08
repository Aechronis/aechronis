package net.aechronis.vanilla.managers

import net.aechronis.vanilla.serdes.PlayerDataDeserializer
import net.aechronis.vanilla.serdes.PlayerDataSerializer
import net.aechronis.vanilla.serdes.PlayerDataSnapshot
import net.kyori.adventure.nbt.BinaryTagIO
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

// loosely based on https://github.com/Quiet-Terminal-Interactive/Cattlelog
object PlayerData {
    private val tracked: MutableSet<Player> = ConcurrentHashMap.newKeySet<Player>()
    private var writer: PlayerDataWriter? = null
    private lateinit var dataPath: Path

    fun init(path: Path): EventNode<Event> {
        val timeStart = System.currentTimeMillis()
        Files.createDirectories(path)
        dataPath = path
        check(writer == null) { "Player data is already initialized" }
        writer = PlayerDataWriter(path)

        val node = EventNode.all("vanilla-playerdata")

        node.addListener(PlayerSpawnEvent::class.java) { event ->
            Commands.allowEnderChest(event.player)
            if (!event.isFirstSpawn) return@addListener
            loadAndTrackPlayer(event.player, path)
        }

        node.addListener(PlayerDisconnectEvent::class.java) { event ->
            saveAndUntrackPlayer(event.player)
        }

        adoptOnlinePlayers(MinecraftServer.getConnectionManager().onlinePlayers, path)
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Playerdata enabled in ${timeLoad}ms")
        return node
    }

    /** Capture on the game thread; the returned future covers serialization and durable writes. */
    fun saveAll(): CompletableFuture<Void> {
        val writer = writer ?: return CompletableFuture.completedFuture(null)
        var failure: Throwable? = null
        val snapshots = ArrayList<PlayerDataSnapshot>(tracked.size)
        for (player in tracked) {
            try {
                snapshots += PlayerDataSnapshot.capture(player)
            } catch (e: Exception) {
                System.err.println("Failed to snapshot player data for ${player.uuid}: ${e.message}")
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        return writer.save(snapshots, retryPending = true).handle { _, error ->
            if (error != null) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
            failure?.let { throw CompletionException(it) }
            null
        }
    }

    fun shutdown() {
        writer?.close()
        writer = null
        tracked.clear()
    }

    /**
     * Adopts players that survived a module-generation replacement. Their live Minestom state is
     * authoritative, so only module-owned state is restored from the old generation's checkpoint.
     * A failed restore aborts generation startup rather than allowing a later save to overwrite
     * valid ender-chest or ignore-list data with empty state.
     */
    internal fun adoptOnlinePlayers(
        players: Collection<Player>,
        path: Path,
    ) {
        players.forEach { player ->
            Commands.allowEnderChest(player)
            restoreModuleState(player, path)
            tracked.add(player)
        }
    }

    private fun restoreModuleState(
        player: Player,
        path: Path,
    ) {
        val playerPath = path.resolve("${player.uuid}.dat")
        if (!Files.exists(playerPath)) return

        Files.newInputStream(playerPath).use { input ->
            val named = BinaryTagIO.reader().readNamed(input, BinaryTagIO.Compression.GZIP)
            PlayerDataDeserializer.deserializeModuleState(player, named.value)
        }
    }

    internal fun loadAndTrackPlayer(
        player: Player,
        path: Path,
    ): Boolean {
        val pending = writer?.pending(player.uuid)
        val loaded =
            if (pending == null) {
                tryLoadPlayer(player, path)
            } else {
                runCatching { PlayerDataDeserializer.deserialize(player, PlayerDataSerializer.serialize(pending)) }
                    .onFailure { error ->
                        System.err.println("Failed to restore pending player data for ${player.uuid}: ${error.message}")
                    }.isSuccess
            }

        if (loaded) tracked.add(player)
        return loaded
    }

    private fun tryLoadPlayer(
        player: Player,
        path: Path,
    ): Boolean {
        val path: Path = path.resolve("${player.uuid}.dat")
        if (!Files.exists(path)) {
            return true
        }

        return runCatching {
            Files.newInputStream(path).use { input ->
                val named = BinaryTagIO.reader().readNamed(input, BinaryTagIO.Compression.GZIP)
                PlayerDataDeserializer.deserialize(player, named.value)
            }
        }.onFailure { error ->
            System.err.println("Failed to load player data for ${player.uuid}: ${error.message}")
        }.isSuccess
    }

    internal fun saveAndUntrackPlayer(player: Player) {
        val shouldSave = tracked.remove(player)
        Commands.closeViewsOf(player)
        try {
            if (!shouldSave) return

            val data =
                try {
                    PlayerDataSnapshot.capture(player)
                } catch (error: Exception) {
                    System.err.println("Failed to snapshot player data for ${player.uuid}: ${error.message}")
                    return
                }

            // Capture before clearing module-owned inventories. Never wait for a tick or disk from
            // this entity callback; reconnects can restore the queued immutable snapshot directly.
            checkNotNull(writer).save(listOf(data)).whenComplete { _, error ->
                if (error != null) {
                    System.err.println("Failed to save player data for ${data.uuid}; queued for retry: ${error.message}")
                }
            }
        } finally {
            Commands.removeEnderChest(player)
            Commands.clearPlayerReferences(player)
        }
    }
}
