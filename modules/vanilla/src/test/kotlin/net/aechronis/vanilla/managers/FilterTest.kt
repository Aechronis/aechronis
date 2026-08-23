package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.PlayerChatEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterTest : ManagerTest() {
    @Test
    fun `clean chat is allowed`() {
        val player = VanillaTest.createPlayer(Pos(0.0, 40.0, 0.0))
        try {
            val event = PlayerChatEvent(player, listOf(player), "This is just a text.")

            Filter.onChat(event)

            assertFalse(event.isCancelled)
        } finally {
            VanillaTest.remove(player)
        }
    }

    @Test
    fun `messages sent within one second are blocked`() {
        val player = VanillaTest.createPlayer(Pos(0.0, 40.0, 0.0))
        try {
            val first = PlayerChatEvent(player, listOf(player), "First message")
            val second = PlayerChatEvent(player, listOf(player), "Second message")

            Filter.onChat(first)
            Filter.onChat(second)

            assertFalse(first.isCancelled)
            assertTrue(second.isCancelled)
        } finally {
            VanillaTest.remove(player)
        }
    }

    @Test
    fun `profane chat is blocked`() {
        val player = VanillaTest.createPlayer(Pos(0.0, 40.0, 0.0))
        try {
            val event = PlayerChatEvent(player, listOf(player), "Leave those scumbags alone!")

            Filter.onChat(event)

            assertTrue(event.isCancelled)
        } finally {
            VanillaTest.remove(player)
        }
    }
}
