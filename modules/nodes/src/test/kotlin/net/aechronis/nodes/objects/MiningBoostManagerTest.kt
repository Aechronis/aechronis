package net.aechronis.nodes.objects

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiningBoostManagerTest {
    @Test
    fun `serialized inactive boosts can be loaded`() {
        MiningBoostManager.load(null)
        val persisted = JsonParser.parseString(MiningBoostManager.toJsonString()).asJsonObject
        assertTrue(persisted.get("haste").isJsonNull)
        assertTrue(persisted.get("boost").isJsonNull)

        MiningBoostManager.load(persisted)

        assertEquals(1, MiningBoostManager.miningMultiplier())
    }
}
