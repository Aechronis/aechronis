package net.aechronis.server.events

import net.minestom.server.coordinate.Pos
import net.minestom.server.event.Event

data class SpawnPointChangedEvent(
    val spawnPoint: Pos,
) : Event
