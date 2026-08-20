package net.aechronis.combat.utils

import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import net.minestom.server.utils.PacketSendingUtils
import kotlin.math.ceil

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
        maxParticles: Int = MAX_TRAIL_PARTICLES,
    ) {
        val distance = from.distance(to)
        val count = particleLinePointCount(distance, spacing, maxParticles)
        if (count == 0) return

        val viewers =
            instance.players.filter {
                distanceSquaredToSegment(it.position, from, to) <= TRAIL_VIEW_DISTANCE * TRAIL_VIEW_DISTANCE
            }
        if (viewers.isEmpty()) return

        if (count == 1) {
            PacketSendingUtils.sendGroupedPacket(viewers, ParticlePacket(particle, from, Pos.ZERO, 0F, 1))
            return
        }

        val direction = to.sub(from)
        for (index in 0 until count) {
            val progress = index.toDouble() / (count - 1).toDouble()
            val point = from.add(direction.mul(progress))
            PacketSendingUtils.sendGroupedPacket(viewers, ParticlePacket(particle, point, Pos.ZERO, 0F, 1))
        }
    }
}

internal fun particleLinePointCount(
    distance: Double,
    spacing: Double = DEFAULT_TRAIL_SPACING,
    maxParticles: Int = MAX_TRAIL_PARTICLES,
): Int {
    require(distance.isFinite() && distance >= 0.0) { "Tracer distance must be finite and non-negative" }
    require(spacing.isFinite() && spacing > 0.0) { "Tracer spacing must be positive and finite" }
    require(maxParticles >= 2) { "Tracer particle budget must be at least two" }
    if (distance == 0.0) return 1

    val segments = ceil(distance / spacing).coerceAtMost((maxParticles - 1).toDouble()).toInt()
    return segments + 1
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
