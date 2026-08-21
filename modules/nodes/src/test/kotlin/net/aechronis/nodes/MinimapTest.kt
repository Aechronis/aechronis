package net.aechronis.nodes

import com.google.gson.JsonParser
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.minimapOpacity
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MinimapTest {
    @Test
    fun `position round trips through text opacity`() {
        for (position in MinimapPosition.entries) {
            val encodedOpacity = minimapOpacity(position).toUByte().toInt()
            assertEquals(position.shaderValue, encodedOpacity - MINIMAP_OPACITY_OFFSET)
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

    private companion object {
        const val MINIMAP_OPACITY_OFFSET = 4
    }
}
