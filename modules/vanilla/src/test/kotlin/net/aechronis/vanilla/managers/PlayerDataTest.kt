package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertFalse

class PlayerDataTest : ManagerTest() {
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
