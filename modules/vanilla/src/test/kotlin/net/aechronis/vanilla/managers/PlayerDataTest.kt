package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerDataTest : ManagerTest() {
    @Test
    fun `adopting an online player preserves live state and tracks later saves`() {
        val player = VanillaTest.createPlayer(Pos(90.5, 40.0, 4.5))
        val path = Files.createTempDirectory("vanilla-playerdata-handoff-test-")
        val ignoredPlayer = UUID.randomUUID()

        try {
            player.health = 4f
            Commands.getEnderChest(player).setItemStack(0, ItemStack.of(Material.DIAMOND, 3))
            Commands.setIgnored(player, setOf(ignoredPlayer))
            PlayerData.adoptOnlinePlayers(listOf(player), path)
            PlayerData.saveAndUntrackPlayer(player, path)

            player.health = 15f
            PlayerData.adoptOnlinePlayers(listOf(player), path)

            assertEquals(15f, player.health, "generation handoff must not reload the stale checkpoint")
            assertTrue(PlayerData.isTracked(player))
            assertEquals(ItemStack.of(Material.DIAMOND, 3), Commands.getEnderChest(player).getItemStack(0))
            assertEquals(setOf(ignoredPlayer), Commands.getIgnored(player))

            PlayerData.saveAndUntrackPlayer(player, path)
            player.health = 1f
            PlayerData.loadPlayer(player, path)
            assertEquals(15f, player.health, "the adopted generation must save subsequent live state")
        } finally {
            VanillaTest.remove(player)
            path.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing player data is treated as a new player`() {
        val player = VanillaTest.createPlayer(Pos(92.5, 40.0, 4.5))
        val path = Files.createTempDirectory("vanilla-playerdata-test-")

        try {
            PlayerData.loadPlayer(player, path)
            assertFalse(PlayerData.hasSavedData(player))
        } finally {
            VanillaTest.remove(player)
            path.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt player data does not prevent the player from joining`() {
        val player = VanillaTest.createPlayer(Pos(94.5, 40.0, 4.5))
        val path = Files.createTempDirectory("vanilla-corrupt-playerdata-test-")
        val file = path.resolve("${player.uuid}.dat")
        Files.writeString(file, "not nbt", StandardOpenOption.CREATE)

        try {
            PlayerData.loadPlayer(player, path)
        } finally {
            VanillaTest.remove(player)
            path.toFile().deleteRecursively()
        }
    }
}
