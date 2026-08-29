package net.aechronis.nodes.objects

import net.aechronis.nodes.constants.DiplomaticRelationship
import net.minestom.server.entity.attribute.Attribute
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelationshipHitboxTest {
    @Test
    fun `relationships use distinct near-default scale codes`() {
        val scales = DiplomaticRelationship.entries.map { relationship ->
            assertNotNull(RelationshipHitbox.encodedScale(relationship, 1.0))
        }

        assertEquals(scales.size, scales.distinct().size)
        assertTrue(scales.all { scale -> abs(scale - 1.0) <= 0.0100000001 })
    }

    @Test
    fun `custom player scale is not overwritten`() {
        assertNull(RelationshipHitbox.encodedScale(DiplomaticRelationship.ENEMY, 0.75))
        assertNull(RelationshipHitbox.packet(42, DiplomaticRelationship.ENEMY, 0.75))
    }

    @Test
    fun `packet contains only the client-side scale watermark`() {
        val packet = assertNotNull(RelationshipHitbox.packet(42, DiplomaticRelationship.ENEMY, 1.0))
        val property = packet.properties.single()

        assertEquals(42, packet.entityId)
        assertEquals(Attribute.SCALE, property.attribute)
        assertEquals(RelationshipHitbox.ENEMY_SCALE, property.value)
        assertEquals(1, property.modifiers.size)
        assertEquals(0.0, property.modifiers.single().amount)
    }
}
