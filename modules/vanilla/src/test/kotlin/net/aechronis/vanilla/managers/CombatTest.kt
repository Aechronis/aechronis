package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombatTest : ManagerTest() {
    @Test
    fun `tag marks both players and clear removes only the selected tag`() {
        val attacker = VanillaTest.createPlayer(Pos(86.5, 40.0, 4.5))
        val victim = VanillaTest.createPlayer(Pos(88.5, 40.0, 4.5))

        Combat.tag(attacker, victim)

        assertTrue(Combat.isInCombat(attacker))
        assertTrue(Combat.isInCombat(victim))

        Combat.clear(attacker)

        assertFalse(Combat.isInCombat(attacker))
        assertTrue(Combat.isInCombat(victim))
        Combat.clear(victim)
        VanillaTest.remove(attacker)
        VanillaTest.remove(victim)
    }
}
