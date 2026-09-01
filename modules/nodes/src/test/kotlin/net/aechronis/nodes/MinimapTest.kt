package net.aechronis.nodes

import com.google.gson.JsonParser
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.minimapOpacity
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MinimapTest {
    @Test
    fun `position and north lock round trip through text opacity`() {
        for (position in MinimapPosition.entries) {
            for (northLocked in listOf(false, true)) {
                val encodedOpacity = minimapOpacity(position, northLocked).toUByte().toInt()
                val raw = encodedOpacity - MINIMAP_OPACITY_OFFSET
                assertEquals(position.shaderValue, raw and 3)
                assertEquals(northLocked, (raw and 4) != 0)
            }
        }
    }

    @Test
    fun `minimap positions parse by id`() {
        for (position in MinimapPosition.entries) {
            assertEquals(position, MinimapPosition.fromId(position.id.uppercase()))
        }
        assertNull(MinimapPosition.fromId("center"))
    }

    @Test
    fun `new residents default to top right and save the preference`() {
        val resident = Resident(UUID.randomUUID(), "minimap-default")

        assertEquals(MinimapPosition.TOP_RIGHT, MinimapPosition.DEFAULT)
        assertEquals(MinimapPosition.DEFAULT, resident.minimapPosition)

        val json = JsonParser.parseString(resident.getSaveState().createJsonString()).asJsonObject
        assertEquals(MinimapPosition.TOP_RIGHT.id, json.get("minimapPosition").asString)
    }

    @Test
    fun `new residents default to north locked and toggling saves the preference`() {
        val resident = Resident(UUID.randomUUID(), "minimap-north-lock")
        assertTrue(resident.minimapNorthLocked)

        assertFalse(resident.toggleMinimapNorthLock())
        assertFalse(resident.minimapNorthLocked)
        var json = JsonParser.parseString(resident.getSaveState().createJsonString()).asJsonObject
        assertFalse(json.get("minimapNorthLocked").asBoolean)

        assertTrue(resident.toggleMinimapNorthLock())
        json = JsonParser.parseString(resident.getSaveState().createJsonString()).asJsonObject
        assertTrue(json.get("minimapNorthLocked").asBoolean)
    }

    private companion object {
        const val MINIMAP_OPACITY_OFFSET = 4
    }
}
