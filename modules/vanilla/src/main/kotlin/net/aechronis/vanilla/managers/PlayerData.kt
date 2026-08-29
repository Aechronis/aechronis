package net.aechronis.vanilla.managers

import net.aechronis.vanilla.serdes.PlayerDataDeserializer
import net.aechronis.vanilla.serdes.PlayerDataSerializer
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// loosely based on https://github.com/Quiet-Terminal-Interactive/Cattlelog
object PlayerData {
    private val tracked: MutableSet<Player> = ConcurrentHashMap.newKeySet<Player>()
    private val pendingDisconnectSaves = ConcurrentHashMap<UUID, CompoundBinaryTag>()
    private lateinit var dataPath: Path

    fun init(path: Path): EventNode<Event> {
        val timeStart = System.currentTimeMillis()
        Files.createDirectories(path)
        dataPath = path

        val node = EventNode.all("vanilla-playerdata")

        node.addListener(PlayerSpawnEvent::class.java) { event ->
            Commands.allowEnderChest(event.player)
            if (!event.isFirstSpawn) return@addListener
            loadAndTrackPlayer(event.player, path)
        }

        node.addListener(PlayerDisconnectEvent::class.java) { event ->
            saveAndUntrackPlayer(event.player, path)
        }

        adoptOnlinePlayers(MinecraftServer.getConnectionManager().onlinePlayers, path)
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Playerdata enabled in ${timeLoad}ms")
        return node
    }

    fun saveAll() {
        if (!::dataPath.isInitialized) return
        saveAll(dataPath)
    }

    internal fun saveAll(path: Path) {
        var failure: Throwable? = null

        for ((uuid, data) in pendingDisconnectSaves) {
            try {
                writePlayerData(uuid, data, path)
                pendingDisconnectSaves.remove(uuid, data)
            } catch (e: Exception) {
                System.err.println("Failed to retry player data save for $uuid: ${e.message}")
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }

        for (player in tracked) {
            try {
                savePlayer(player, path)
            } catch (e: Exception) {
                System.err.println("Failed to save player data for ${player.uuid}: ${e.message}")
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    fun hasSavedData(player: Player): Boolean = ::dataPath.isInitialized && Files.exists(dataPath.resolve("${player.uuid}.dat"))

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

    internal fun isTracked(player: Player): Boolean = player in tracked

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
        val pending = pendingDisconnectSaves.remove(player.uuid)
        val loaded =
            if (pending == null) {
                tryLoadPlayer(player, path)
            } else {
                runCatching { PlayerDataDeserializer.deserialize(player, pending) }
                    .onFailure { error ->
                        pendingDisconnectSaves.putIfAbsent(player.uuid, pending)
                        System.err.println("Failed to restore pending player data for ${player.uuid}: ${error.message}")
                    }.isSuccess
            }

        if (loaded) tracked.add(player)
        return loaded
    }

    fun loadPlayer(
        player: Player,
        path: Path,
    ) {
        tryLoadPlayer(player, path)
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

    internal fun saveAndUntrackPlayer(
        player: Player,
        path: Path,
    ) {
        val shouldSave = tracked.remove(player)
        Commands.closeViewsOf(player)
        try {
            if (!shouldSave) return

            val data =
                try {
                    PlayerDataSerializer.serialize(player)
                } catch (error: Exception) {
                    System.err.println("Failed to snapshot player data for ${player.uuid}: ${error.message}")
                    return
                }

            try {
                writePlayerData(player.uuid, data, path)
                pendingDisconnectSaves.remove(player.uuid)
            } catch (error: Exception) {
                pendingDisconnectSaves[player.uuid] = data
                System.err.println("Failed to save player data for ${player.uuid}; queued for retry: ${error.message}")
            }
        } finally {
            Commands.removeEnderChest(player)
            Commands.clearPlayerReferences(player)
        }
    }

    private fun savePlayer(
        player: Player,
        path: Path,
    ) {
        val data = PlayerDataSerializer.serialize(player)
        writePlayerData(player.uuid, data, path)
    }

    private fun writePlayerData(
        uuid: UUID,
        data: CompoundBinaryTag,
        path: Path,
    ) {
        val target = path.resolve("$uuid.dat")
        val temporary = path.resolve(".$uuid.${Thread.currentThread().threadId()}.tmp")

        try {
            Files.newOutputStream(temporary).use { out ->
                BinaryTagIO.writer().writeNamed(
                    SimpleImmutableEntry("", data),
                    out,
                    BinaryTagIO.Compression.GZIP,
                )
            }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
