package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BlocksTest : ManagerTest() {
    @Test
    fun `initialization does not duplicate conversion variants`() {
        val variants = Blocks.variants.mapValues { it.value.toList() }
        val outputs = Blocks.outputsByInput.mapValues { it.value.toList() }

        Blocks.init()

        assertEquals(variants, Blocks.variants.mapValues { it.value.toList() })
        assertEquals(outputs, Blocks.outputsByInput.mapValues { it.value.toList() })
    }
}
