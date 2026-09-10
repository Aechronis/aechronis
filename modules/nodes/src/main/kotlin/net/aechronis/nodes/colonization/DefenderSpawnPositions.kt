package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import kotlin.random.Random

private const val MAX_LOCAL_SPAWN_COLUMNS = 96
private const val MIN_LOCAL_SPAWN_RADIUS = 8
private const val MAX_LOCAL_SPAWN_RADIUS = 20
private const val LOCAL_SPAWN_VERTICAL_SEARCH = 12

internal fun localDefenderSpawnColumns(
    targetTown: Town,
    center: Pos,
    random: Random = Random.Default,
): List<Pos> {
    val minimumSquared = MIN_LOCAL_SPAWN_RADIUS * MIN_LOCAL_SPAWN_RADIUS
    val maximumSquared = MAX_LOCAL_SPAWN_RADIUS * MAX_LOCAL_SPAWN_RADIUS
    return buildList {
        for (offsetX in -MAX_LOCAL_SPAWN_RADIUS..MAX_LOCAL_SPAWN_RADIUS) {
            for (offsetZ in -MAX_LOCAL_SPAWN_RADIUS..MAX_LOCAL_SPAWN_RADIUS) {
                val distanceSquared = offsetX * offsetX + offsetZ * offsetZ
                if (distanceSquared !in minimumSquared..maximumSquared) continue
                val x = center.blockX() + offsetX
                val z = center.blockZ() + offsetZ
                if (Territory.fromBlock(x, z)?.town !== targetTown) continue
                add(Pos(x + 0.5, center.y, z + 0.5))
            }
        }
    }.shuffled(random).take(MAX_LOCAL_SPAWN_COLUMNS)
}

internal fun coreTerritoryGuardColumns(
    targetTown: Town,
    referenceY: Double,
    maximumColumns: Int,
    random: Random = Random.Default,
): List<Pos> {
    val territory = Territory.fromId(targetTown.home) ?: return emptyList()
    if (territory.town !== targetTown) return emptyList()
    val chunks = buildSet {
        add(territory.core)
        addAll(territory.chunks)
    }
    return randomTerritoryColumns(chunks, referenceY, maximumColumns, random)
        .filter { position ->
            Territory.fromBlock(position.blockX(), position.blockZ()) === territory
        }
}

internal fun randomTerritoryColumns(
    chunks: Collection<Coord>,
    referenceY: Double,
    maximumColumns: Int,
    random: Random = Random.Default,
): List<Pos> {
    require(referenceY.isFinite()) { "Guard reference height must be finite" }
    require(maximumColumns >= 0) { "Maximum guard columns cannot be negative" }
    if (maximumColumns == 0) return emptyList()
    return buildList {
        chunks.distinct().forEach { chunk ->
            for (offsetX in 0..<16) {
                for (offsetZ in 0..<16) {
                    add(Pos(chunk.x * 16 + offsetX + 0.5, referenceY, chunk.z * 16 + offsetZ + 0.5))
                }
            }
        }
    }.shuffled(random).take(maximumColumns)
}

internal fun resolveSafeSurfaceNear(
    instance: Instance,
    horizontalPosition: Pos,
    referenceY: Int,
): Pos? {
    val dimension = instance.cachedDimensionType
    val maximumY = (referenceY + LOCAL_SPAWN_VERTICAL_SEARCH).coerceAtMost(dimension.maxY() - 2)
    val minimumY = (referenceY - LOCAL_SPAWN_VERTICAL_SEARCH).coerceAtLeast(dimension.minY() + 1)
    for (y in maximumY downTo minimumY) {
        val candidate = horizontalPosition.withY(y.toDouble())
        if (isSafeDefenderStandingPosition(instance, candidate)) return candidate
    }
    return null
}

internal fun resolveSafeGuardSurface(
    instance: Instance,
    horizontalPosition: Pos,
    referenceY: Int,
): Pos? {
    resolveSafeSurfaceNear(instance, horizontalPosition, referenceY)?.let { return it }
    val dimension = instance.cachedDimensionType
    val maximumY = dimension.maxY() - 2
    val minimumY = dimension.minY() + 1
    var offset = LOCAL_SPAWN_VERTICAL_SEARCH + 1
    while (referenceY + offset <= maximumY || referenceY - offset >= minimumY) {
        val above = referenceY + offset
        if (above <= maximumY) {
            val candidate = horizontalPosition.withY(above.toDouble())
            if (isSafeDefenderStandingPosition(instance, candidate)) return candidate
        }
        val below = referenceY - offset
        if (below >= minimumY) {
            val candidate = horizontalPosition.withY(below.toDouble())
            if (isSafeDefenderStandingPosition(instance, candidate)) return candidate
        }
        offset += 1
    }
    return null
}
