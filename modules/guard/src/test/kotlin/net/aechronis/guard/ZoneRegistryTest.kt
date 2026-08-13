package net.aechronis.guard

import net.aechronis.guard.objects.Zone
import net.aechronis.guard.objects.ZoneBounds
import net.aechronis.guard.storage.ZoneRegistry
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class ZoneRegistryTest {
    @Test
    fun `highest priority matching zone wins`() {
        val instanceId = UUID.randomUUID()
        val registry = ZoneRegistry()
        registry.add(Zone("low", instanceId, ZoneBounds(0, 0, 0, 10, 10, 10), priority = 1))
        registry.add(Zone("high", instanceId, ZoneBounds(0, 0, 0, 10, 10, 10), priority = 2))

        assertEquals("high", registry.find(instanceId, 5, 5, 5)?.name)
    }

    @Test
    fun `find all returns containing zones in priority order`() {
        val instanceId = UUID.randomUUID()
        val registry = ZoneRegistry()
        registry.add(Zone("low", instanceId, ZoneBounds(0, 0, 0, 10, 10, 10), priority = 1))
        registry.add(Zone("high", instanceId, ZoneBounds(0, 0, 0, 10, 10, 10), priority = 2))
        registry.add(Zone("outside", instanceId, ZoneBounds(20, 20, 20, 30, 30, 30), priority = 3))

        assertEquals(listOf("high", "low"), registry.findAll(instanceId, 5, 5, 5).map(Zone::name))
    }
}
