package net.aechronis.combat.utils

import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import net.minestom.server.utils.PacketSendingUtils
import kotlin.math.min

private const val DEFAULT_TRAIL_SPACING = 1.0
private const val MAX_TRAIL_PARTICLES = 96
private const val TRAIL_VIEW_DISTANCE = 64.0

object Particles {
    fun bloodParticle(
        instance: Instance,
        pos: Pos,
    ) = instance.sendGroupedPacket(ParticlePacket(Particle.FALLING_DUST.withBlock(Block.REDSTONE_BLOCK), pos, Pos.ZERO, 0F, 10))

    fun dustParticle(
        instance: Instance,
        pos: Pos,
    ) = instance.sendGroupedPacket(ParticlePacket(Particle.DUST, pos, Pos.ZERO, 0F, 10))

    fun particleLine(
        instance: Instance,
        particle: Particle,
        from: Pos,
        to: Pos,
        spacing: Double = DEFAULT_TRAIL_SPACING,
    ) {
        val direction = to.sub(from)
        val distance = from.distance(to)
        val steps = min((distance / spacing).toInt(), MAX_TRAIL_PARTICLES - 1)
        if (steps <= 0) return

        val viewers =
            instance.players.filter {
                distanceSquaredToSegment(it.position, from, to) <= TRAIL_VIEW_DISTANCE * TRAIL_VIEW_DISTANCE
            }
        if (viewers.isEmpty()) return

        val step = direction.div(steps.toDouble())
        var current = from

        for (i in 0..steps) {
            PacketSendingUtils.sendGroupedPacket(viewers, ParticlePacket(particle, current, Pos.ZERO, 0F, 1))
            current = current.add(step)
        }
    }
}

internal fun distanceSquaredToSegment(
    point: Point,
    from: Point,
    to: Point,
): Double {
    val dx = to.x() - from.x()
    val dy = to.y() - from.y()
    val dz = to.z() - from.z()
    val lengthSquared = dx * dx + dy * dy + dz * dz
    if (lengthSquared == 0.0) return point.distanceSquared(from)

    val progress =
        (((point.x() - from.x()) * dx + (point.y() - from.y()) * dy + (point.z() - from.z()) * dz) / lengthSquared)
            .coerceIn(0.0, 1.0)
    val closest = Vec(from.x() + dx * progress, from.y() + dy * progress, from.z() + dz * progress)
    return point.distanceSquared(closest)
}
