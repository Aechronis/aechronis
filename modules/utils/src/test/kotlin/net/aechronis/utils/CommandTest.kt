package net.aechronis.utils

import net.minestom.server.MinecraftServer
import net.minestom.server.command.ConsoleSender
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandTest {
    private lateinit var player: Player

    @BeforeAll
    fun setUp() {
        MinecraftServer.init()
        player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "TestPlayer"))
    }

    @AfterAll
    fun tearDown() {
        MinecraftServer.stopCleanly()
    }

    @Test
    fun `default executor runs for a permitted player`() {
        val command = TestCommand(allowedPermissions = setOf("example.use"))
        var executed = false
        command.setDefaultExecutor { _, _ -> executed = true }

        requireNotNull(command.defaultExecutor).apply(player, CommandContext("example"))

        assertTrue(executed)
        assertEquals("example.use", command.checkedPermission)
    }

    @Test
    fun `default executor rejects a denied player`() {
        val command = TestCommand()
        var executed = false
        command.setDefaultExecutor { _, _ -> executed = true }

        requireNotNull(command.defaultExecutor).apply(player, CommandContext("example"))

        assertFalse(executed)
    }

    @Test
    fun `default executor rejects a console sender`() {
        val command = TestCommand(allowedPermissions = setOf("example.use"))
        var executed = false
        command.setDefaultExecutor { _, _ -> executed = true }

        requireNotNull(command.defaultExecutor).apply(ConsoleSender(), CommandContext("example"))

        assertFalse(executed)
    }

    @Test
    fun `null permission does not restrict execution`() {
        val command = TestCommand(permission = null)
        var executed = false
        command.setDefaultExecutor { _, _ -> executed = true }

        requireNotNull(command.defaultExecutor).apply(player, CommandContext("example"))

        assertTrue(executed)
    }

    @Test
    fun `syntax can override the command permission`() {
        val command = TestCommand(allowedPermissions = setOf("example.admin"))
        val argument = ArgumentType.String("value")
        var executed = false
        command.addSyntax("example.admin", { _, _ -> executed = true }, argument)

        command.syntaxes
            .single()
            .executor
            .apply(player, CommandContext("example value"))

        assertTrue(executed)
        assertEquals("example.admin", command.checkedPermission)
    }

    @Test
    fun `permission lookup fails closed without a provider`() {
        assertFalse(player.hasPermission("example.use"))
    }

    private class TestCommand(
        permission: String? = "example.use",
        private val allowedPermissions: Set<String> = emptySet(),
    ) : Command("example", permission, "example-alias") {
        var checkedPermission: String? = null
            private set

        override fun hasPermission(
            player: Player,
            permission: String?,
        ): Boolean {
            checkedPermission = permission
            return permission == null || permission in allowedPermissions
        }
    }

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}
