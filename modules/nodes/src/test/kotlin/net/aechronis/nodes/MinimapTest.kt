package net.aechronis.nodes

import com.google.gson.JsonParser
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.MinimapYawCodec
import net.aechronis.nodes.objects.Resident
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MinimapTest {
    @Test
    fun `position and yaw round trip through text opacity`() {
        for (yawIndex in 0 until YAW_BUCKET_COUNT) {
            for (position in MinimapPosition.entries) {
                val encodedOpacity = MinimapYawCodec.opacity(yawIndex, position).toUByte().toInt()
                val payload = encodedOpacity - YAW_OPACITY_OFFSET

                assertEquals(yawIndex, payload / POSITION_BUCKET_COUNT)
                assertEquals(position.shaderValue, payload % POSITION_BUCKET_COUNT)
            }
        }
    }

    @Test
    fun `opacity codec clamps yaw without corrupting position`() {
        for (position in MinimapPosition.entries) {
            assertEquals(
                MinimapYawCodec.opacity(0, position),
                MinimapYawCodec.opacity(Int.MIN_VALUE, position),
            )
            assertEquals(
                MinimapYawCodec.opacity(YAW_BUCKET_COUNT - 1, position),
                MinimapYawCodec.opacity(Int.MAX_VALUE, position),
            )
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
        const val YAW_BUCKET_COUNT = 62
        const val POSITION_BUCKET_COUNT = 4
        const val YAW_OPACITY_OFFSET = 4
    }
}
