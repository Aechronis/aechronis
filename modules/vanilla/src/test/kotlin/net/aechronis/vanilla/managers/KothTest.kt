package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.objects.KothZone
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KothTest : ManagerTest() {
    @Test
    fun `zone normalizes both corners and includes all configured blocks`() {
        val zone = KothZone(BlockVec(10, 20, 30), BlockVec(8, 18, 28))

        assertTrue(zone.contains(Pos(8.0, 18.0, 28.0)))
        assertTrue(zone.contains(Pos(10.99, 20.99, 30.99)))
        assertFalse(zone.contains(Pos(7.99, 19.0, 29.0)))
        assertFalse(zone.contains(Pos(9.0, 21.0, 29.0)))
    }

    @Test
    fun `a command-created koth needs corners before it can start`() {
        val name = "koth-test-${System.nanoTime()}"
        assertTrue(Koth.add(name, 10, 60, 100.0))
        assertTrue(Files.readString(VanillaTest.pluginRoot.resolve("koth.json")).contains(name))
        assertFalse(Koth.start(name))
        assertTrue(Koth.remove(name))
    }
}
