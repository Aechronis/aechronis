package net.aechronis.nodes.objects

import net.aechronis.nodes.constants.DiplomaticRelationship
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NametagTest {
    @Test
    fun `stale removal is suppressed after member changes teams`() {
        val state = Nametag.ViewerState()
        state.createTown(1, DiplomaticRelationship.NEUTRAL, listOf("Player"))
        state.createTown(2, DiplomaticRelationship.NEUTRAL, emptyList())

        assertTrue(state.addMember(2, "Player"))
        assertFalse(state.removeMember(1, "Player"))
        assertTrue(state.removeMember(2, "Player"))
        assertFalse(state.removeMember(2, "Player"))
    }

    @Test
    fun `destroying a team clears its tracked members`() {
        val state = Nametag.ViewerState()
        state.createTown(1, DiplomaticRelationship.NEUTRAL, listOf("Player"))

        assertTrue(state.removeTown(1))
        assertFalse(state.removeMember(1, "Player"))
        assertFalse(state.removeTown(1))
    }
}
