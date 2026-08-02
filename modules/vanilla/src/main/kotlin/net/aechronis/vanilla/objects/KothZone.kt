package net.aechronis.vanilla.objects

import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos

class KothZone(
    cornerOne: BlockVec,
    cornerTwo: BlockVec,
) {
    private val minX = minOf(cornerOne.blockX(), cornerTwo.blockX())
    private val minY = minOf(cornerOne.blockY(), cornerTwo.blockY())
    private val minZ = minOf(cornerOne.blockZ(), cornerTwo.blockZ())
    private val maxX = maxOf(cornerOne.blockX(), cornerTwo.blockX())
    private val maxY = maxOf(cornerOne.blockY(), cornerTwo.blockY())
    private val maxZ = maxOf(cornerOne.blockZ(), cornerTwo.blockZ())

    val center: Pos =
        Pos(
            (minX + maxX + 1) / 2.0,
            (minY + maxY + 1) / 2.0,
            (minZ + maxZ + 1) / 2.0,
        )

    fun contains(position: Pos): Boolean =
        position.x() >= minX &&
            position.x() < maxX + 1 &&
            position.y() >= minY &&
            position.y() < maxY + 1 &&
            position.z() >= minZ &&
            position.z() < maxZ + 1
}
