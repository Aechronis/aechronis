package net.aechronis.server

import net.kyori.adventure.text.Component
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import java.util.UUID

/** Redirects synchronous command replies without changing the command's player identity. */
object CommandOutput {
    private data class Capture(
        val uuid: UUID,
        val receive: (Component) -> Unit,
    )

    private val current = ThreadLocal<Capture>()

    fun <T> capture(
        uuid: UUID,
        receive: (Component) -> Unit,
        action: () -> T,
    ): T {
        val previous = current.get()
        current.set(Capture(uuid, receive))
        return try {
            action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun send(
        sender: CommandSender,
        message: Component,
    ) {
        val capture = current.get()
        if (capture != null && sender is Player && sender.uuid == capture.uuid) {
            capture.receive(message)
        } else {
            sender.sendMessage(message)
        }
    }

    /** A broadcast is also part of the initiating player's command output. */
    fun broadcast(message: Component) {
        current.get()?.receive?.invoke(message)
    }
}
