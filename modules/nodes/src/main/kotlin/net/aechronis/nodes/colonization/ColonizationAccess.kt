package net.aechronis.nodes.colonization

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.CHUNK_SIZE
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Port
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town

internal enum class ColonizationAccess {
    BORDER,
    PORT,
}

internal fun canStartColonization(
    resident: Resident?,
    attackingTown: Town,
): Boolean {
    if (resident?.town !== attackingTown) return false
    if (attackingTown.nation == null) return false
    return resident === attackingTown.leader || attackingTown.officers.contains(resident)
}

internal fun colonizationAccess(
    attackingNation: Nation,
    targetTown: Town,
    ports: Iterable<Port> = Nodes.buildings.filterIsInstance<Port>(),
    portOwner: (Port) -> Town? = Port::getOwner,
): ColonizationAccess? {
    val targetTerritories = targetTown.territories
        .mapNotNull(Nodes.territories::get)
        .filter { territory -> territory.town === targetTown }

    if (targetTerritories.any { territory -> territory.borders(attackingNation) }) {
        return ColonizationAccess.BORDER
    }

    val nationPorts = ports.filter { port -> portOwner(port)?.nation === attackingNation }
    if (
        nationPorts.any { port ->
            val maxDistanceSquared = port.maxWarpDistance.toLong() * port.maxWarpDistance.toLong()
            targetTerritories.any { territory ->
                territory.chunks.any { chunk ->
                    val dx = (port.chunkX.toLong() - chunk.x.toLong()) * CHUNK_SIZE
                    val dz = (port.chunkZ.toLong() - chunk.z.toLong()) * CHUNK_SIZE
                    dx * dx + dz * dz <= maxDistanceSquared
                }
            }
        }
    ) {
        return ColonizationAccess.PORT
    }

    return null
}
