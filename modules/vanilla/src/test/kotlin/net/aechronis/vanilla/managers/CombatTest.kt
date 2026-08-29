package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import kotlin.test.Test
import kotlin.test.assertFailsWith
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

    @Test
    fun `combat tags survive a module state handoff`() {
        val attacker = VanillaTest.createPlayer(Pos(90.5, 40.0, 4.5))
        val victim = VanillaTest.createPlayer(Pos(92.5, 40.0, 4.5))

        try {
            Combat.tag(attacker, victim)
            val payload = Combat.captureTransientState()
            Combat.clear(attacker)
            Combat.clear(victim)

            Combat.restoreTransientState(payload, onlinePlayers = listOf(attacker, victim))

            assertTrue(Combat.isInCombat(attacker))
            assertTrue(Combat.isInCombat(victim))
        } finally {
            Combat.clear(attacker)
            Combat.clear(victim)
            VanillaTest.remove(attacker)
            VanillaTest.remove(victim)
        }
    }

    @Test
    fun `corrupt combat tag handoff fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            Combat.restoreTransientState(byteArrayOf(0, 0, 0, 99), onlinePlayers = emptyList())
        }
    }
}
