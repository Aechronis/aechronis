package net.aechronis.nodes.objects

import net.aechronis.nodes.PLAYER_ARROW_ICON_CODEPOINT
import net.aechronis.nodes.PLAYER_ICON_CODEPOINT
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinimapMarkerRendererTest {
    @Test
    fun `player marker is an arrow when north locked and a dot when heading up`() {
        val northLockedMarkers = MinimapMarkerRenderer.render(viewerSnapshot(northLocked = true), 0, 0, 4)
        val headingUpMarkers = MinimapMarkerRenderer.render(viewerSnapshot(northLocked = false), 0, 0, 4)

        assertTrue(containsCodepoint(northLockedMarkers, PLAYER_ARROW_ICON_CODEPOINT))
        assertFalse(containsCodepoint(northLockedMarkers, PLAYER_ICON_CODEPOINT))

        assertTrue(containsCodepoint(headingUpMarkers, PLAYER_ICON_CODEPOINT))
        assertFalse(containsCodepoint(headingUpMarkers, PLAYER_ARROW_ICON_CODEPOINT))
    }

    private fun containsCodepoint(component: Component, codepoint: Int): Boolean {
        val target = codepoint.toChar().toString()
        if (component is TextComponent && component.content() == target) return true
        return component.children().any { containsCodepoint(it, codepoint) }
    }

    private fun viewerSnapshot(northLocked: Boolean) = MinimapViewerSnapshot(
        residentTown = null,
        residentNation = null,
        alliedNations = emptySet(),
        enemyNations = emptySet(),
        warEnabled = false,
        permanentWaypoints = emptyList(),
        deathWaypoint = null,
        northLocked = northLocked,
    )
}
