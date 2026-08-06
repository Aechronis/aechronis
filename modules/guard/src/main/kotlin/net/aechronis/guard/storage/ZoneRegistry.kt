package net.aechronis.guard.storage

import net.aechronis.guard.objects.Zone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class ZoneRegistry {
    private val zones = CopyOnWriteArrayList<Zone>()

    fun all(): List<Zone> = zones.toList()

    fun get(name: String): Zone? = zones.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun add(zone: Zone) {
        require(zone.name.isNotBlank()) { "Zone name cannot be blank" }
        require(zones.none { it.name.equals(zone.name, ignoreCase = true) }) { "Zone already exists: ${zone.name}" }
        zones += zone
    }

    fun replace(zone: Zone) {
        val index = zones.indexOfFirst { it.name.equals(zone.name, ignoreCase = true) }
        require(index >= 0) { "Unknown zone: ${zone.name}" }
        zones[index] = zone
    }

    fun remove(name: String): Zone? {
        val zone = get(name) ?: return null
        zones.remove(zone)
        return zone
    }

    fun find(
        instanceId: UUID,
        x: Int,
        y: Int,
        z: Int,
    ): Zone? =
        zones
            .asSequence()
            .filter { it.instanceId == instanceId && it.bounds.contains(x, y, z) }
            .sortedWith(compareByDescending<Zone> { it.priority }.thenBy { it.name.lowercase() })
            .firstOrNull()

    fun replaceAll(values: Collection<Zone>) {
        zones.clear()
        values.forEach(::add)
    }
}
