package net.aechronis.combat.utils

import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle

object Particles {
    fun bloodParticle(
        instance: Instance,
        pos: Pos,
    ) = instance.sendGroupedPacket(ParticlePacket(Particle.FALLING_DUST.withBlock(Block.REDSTONE_BLOCK), pos, Pos(0.0, 0.0, 0.0), 0F, 10))

    fun dustParticle(
        instance: Instance,
        pos: Pos,
    ) = instance.sendGroupedPacket(ParticlePacket(Particle.DUST, pos, Pos(0.0, 0.0, 0.0), 0F, 10))

    fun particleLine(
        instance: Instance,
        particle: Particle,
        from: Pos,
        to: Pos,
        spacing: Double = 0.5,
    ) {
        val direction = to.sub(from)
        val distance = from.distance(to)
        val steps = (distance / spacing).toInt()
        if (steps <= 0) return

        val step = direction.div(steps.toDouble())
        var current = from

        for (i in 0..steps) {
            instance.sendGroupedPacket(ParticlePacket(particle, current, Pos.ZERO, 0F, 1))
            current = current.add(step)
        }
    }
}
