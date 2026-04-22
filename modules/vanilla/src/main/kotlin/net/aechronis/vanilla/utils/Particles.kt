package net.aechronis.vanilla.utils

import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle

object Particles {
    fun waxParticle(
        instance: Instance,
        pos: Pos,
    ) = instance.sendGroupedPacket(ParticlePacket(Particle.WAX_ON, pos, Pos(0.0, 0.0, 0.0), 0F, 10))

    fun dustParticle(
        instance: Instance,
        pos: Pos,
    ) = instance.sendGroupedPacket(ParticlePacket(Particle.DUST, pos, Pos(0.0, 0.0, 0.0), 0F, 10))
}
