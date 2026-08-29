package net.aechronis.server.modules

import net.kyori.adventure.text.Component
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent

internal class PlayerAdmissionGate(
    private val isAcceptingPlayers: () -> Boolean,
) {
    fun install(eventNode: EventNode<Event>) {
        val admissionNode: EventNode<Event> = EventNode.all("module-admission").setPriority(Int.MAX_VALUE)
        admissionNode.addListener(AsyncPlayerPreLoginEvent::class.java) { event ->
            rejectUnlessRunning { message -> event.connection.kick(message) }
        }
        admissionNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            rejectUnlessRunning { message -> event.player.kick(message) }
        }
        eventNode.addChild(admissionNode)
    }

    internal fun rejectUnlessRunning(kick: (Component) -> Unit): Boolean {
        if (isAcceptingPlayers()) return false
        kick(RETRY_MESSAGE)
        return true
    }

    private companion object {
        val RETRY_MESSAGE: Component = Component.text("Server modules are changing; please reconnect in a moment")
    }
}
