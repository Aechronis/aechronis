package net.aechronis.vanilla.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PunishTest {
    @Test
    fun `parses compound durations`() {
        assertEquals(86_460_000L, Punish.duration("1d1m"))
        assertEquals(7_200_000L, Punish.duration("2h"))
        assertNull(Punish.duration("1month"))
        assertNull(Punish.duration("0m"))
    }

    @Test
    fun `every rule template has five strikes`() {
        assert(Punish.templates.isNotEmpty())
        assert(Punish.templates.all { it.tiers.size == 5 })
    }
}
