package net.aechronis.server.modules

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class ModuleContextTest {
    @Test
    fun `transient state is copied and remains available for rollback`() {
        val context = ModuleContext()
        val published = byteArrayOf(1, 2, 3)
        context.publishTransientState("vanilla:test", published)
        published[0] = 9

        val firstRead = context.peekTransientState("vanilla:test")!!
        assertContentEquals(byteArrayOf(1, 2, 3), firstRead)
        firstRead[1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), context.peekTransientState("vanilla:test"))
        context.clearTransientState("vanilla:test")
        assertNull(context.peekTransientState("vanilla:test"))
    }
}
