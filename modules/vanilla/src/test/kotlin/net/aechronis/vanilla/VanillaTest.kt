package net.aechronis.vanilla

import net.aechronis.utils.createTestServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.BeforeTest

object VanillaTest {
    private lateinit var server: InstanceContainer
    private lateinit var root: Path
    private var storageDirectoryAtInitialization = false

    val instance: InstanceContainer
        get() {
            initialize()
            return server
        }

    val pluginRoot: Path
        get() {
            initialize()
            return root
        }

    @Synchronized
    private fun initialize() {
        if (::server.isInitialized) return

        server = createTestServer()
        root = Files.createTempDirectory("vanilla-test-")
        Vanilla.init(VanillaConfig(path = root.toString(), playerDataEnabled = false))
        storageDirectoryAtInitialization = Files.exists(root.resolve("storage"))
        Runtime.getRuntime().addShutdownHook(Thread { root.toFile().deleteRecursively() })
    }

    val storageDirectoryCreatedOnInit: Boolean
        get() {
            initialize()
            return storageDirectoryAtInitialization
        }

    fun createPlayer(position: Pos): Player {
        initialize()
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "test"))
        player.setInstance(instance, position).join()
        player.health = 20f
        return player
    }

    fun remove(player: Player) {
        player.remove()
    }

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}

abstract class ManagerTest {
    @BeforeTest
    fun startTestServer() {
        VanillaTest.instance
    }
}
