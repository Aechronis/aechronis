package net.aechronis.server.modules

import net.kyori.adventure.text.Component
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerAdmissionGateTest {
    @Test
    fun `gate is installed after generation listeners`() {
        val root: EventNode<Event> = EventNode.all("test-root")
        PlayerAdmissionGate { true }.install(root)

        val admissionNode = root.children.single()
        assertEquals(Int.MAX_VALUE, admissionNode.priority)
    }

    @Test
    fun `players are rejected outside the running phase`() {
        var accepting = false
        var rejection: Component? = null
        val gate = PlayerAdmissionGate { accepting }

        assertTrue(gate.rejectUnlessRunning { rejection = it })
        assertTrue(rejection != null)

        accepting = true
        rejection = null
        assertFalse(gate.rejectUnlessRunning { rejection = it })
        assertTrue(rejection == null)
    }
}
