package net.aechronis.vanilla.playerdata

import net.kyori.adventure.nbt.BinaryTagIO
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.Map

// loosely based on https://github.com/Quiet-Terminal-Interactive/Cattlelog
object PlayerData {
    fun init(path: Path) {
        Files.createDirectories(path)

        val node = EventNode.all("vanilla-playerdata")

        node.addListener(PlayerSpawnEvent::class.java) { event ->
            if (!event!!.isFirstSpawn) return@addListener
            loadPlayer(event.player, path)
        }

        node.addListener(PlayerDisconnectEvent::class.java) { event ->
            savePlayer(event.player, path)
        }

        MinecraftServer.getGlobalEventHandler().addChild(node)
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
                Map.entry("", data),
                out,
                BinaryTagIO.Compression.GZIP,
            )
        }
    }
}
