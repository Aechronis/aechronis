package net.aechronis.combat.events

import net.minestom.server.coordinate.Pos
import net.minestom.server.event.trait.InstanceEvent
import net.minestom.server.instance.Instance
import java.util.Collections
import java.util.UUID

class ExplosionBlockDamageEvent(
    instance: Instance,
    val position: Pos,
    val sourceUuid: UUID?,
    val sourceName: String?,
    changes: List<ExplosionBlockChange>,
) : InstanceEvent {
    private val eventInstance = instance
    val changes: List<ExplosionBlockChange> = Collections.unmodifiableList(ArrayList(changes))

    override fun getInstance(): Instance = eventInstance
}
