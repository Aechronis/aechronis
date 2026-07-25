package io.github.openminigameserver.worldedit.event

import net.minestom.server.event.trait.InstanceEvent
import net.minestom.server.instance.Instance
import java.util.Collections
import java.util.UUID

// a large edit may make multiple events because each completed native batch is dispatched separately.
class WorldEditBlockChangesEvent(
    val actorUuid: UUID,
    val actorName: String,
    instance: Instance,
    changes: List<WorldEditBlockChange>,
) : InstanceEvent {
    private val eventInstance = instance
    val changes: List<WorldEditBlockChange> = Collections.unmodifiableList(ArrayList(changes))

    override fun getInstance(): Instance = eventInstance
}
