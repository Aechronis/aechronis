package net.aechronis.vanilla.managers

import net.aechronis.vanilla.serdes.PlayerDataDeserializer
import net.aechronis.vanilla.serdes.PlayerDataSerializer
import net.kyori.adventure.nbt.BinaryTagIO
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.concurrent.ConcurrentHashMap

// loosely based on https://github.com/Quiet-Terminal-Interactive/Cattlelog
object PlayerData {
    private val tracked: MutableSet<Player> = ConcurrentHashMap.newKeySet<Player>()
    private lateinit var dataPath: Path

    fun init(path: Path) {
        val timeStart = System.currentTimeMillis()
        Files.createDirectories(path)
        dataPath = path

        val node = EventNode.all("vanilla-playerdata")

        node.addListener(PlayerSpawnEvent::class.java) { event ->
            tracked.add(event.player)
            if (!event.isFirstSpawn) return@addListener
            loadPlayer(event.player, path)
        }

        node.addListener(PlayerDisconnectEvent::class.java) { event ->
            tracked.remove(event.player)
            savePlayer(event.player, path)
        }

        MinecraftServer.getGlobalEventHandler().addChild(node)
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Playerdata enabled in ${timeLoad}ms")
    }

    fun saveAll() {
        if (!::dataPath.isInitialized) return
        for (player in tracked) {
            try {
                savePlayer(player, dataPath)
            } catch (e: Exception) {
                System.err.println("Failed to save player data for ${player.uuid}: ${e.message}")
            }
        }
    }

    fun loadPlayer(
        player: Player,
        path: Path,
    ) {
        val path: Path = path.resolve("${player.uuid}.dat")
        if (!Files.exists(path)) {
            return
        }

        Files.newInputStream(path).use { input ->
            val named = BinaryTagIO.reader().readNamed(input, BinaryTagIO.Compression.GZIP)

            PlayerDataDeserializer.deserialize(player, named.value)
        }
    }

    private fun savePlayer(
        player: Player,
        path: Path,
    ) {
        val data = PlayerDataSerializer.serialize(player)

        val path: Path = path.resolve("${player.uuid}.dat")

        Files.newOutputStream(path).use { out ->
            BinaryTagIO.writer().writeNamed(
                SimpleImmutableEntry("", data),
                out,
                BinaryTagIO.Compression.GZIP,
            )
        }
    }
}
