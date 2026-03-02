package luna.nodes.combat.objects

import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

class Explosion(
    val instance: Instance,
    val pos: Pos,
    val radius: Int,
    val fire: Double,
) {
    init {
        CompletableFuture.runAsync {
            val radiusSquared = radius * radius
            val positions = mutableListOf<Pos>()

            // Collect all positions and send particles
            for (x in -radius..radius) {
                val xSquared = x * x
                for (y in -radius..radius) {
                    val ySquared = y * y
                    for (z in -radius..radius) {
                        // Early distance check using squared distance (faster than distance())
                        if (xSquared + ySquared + z * z > radiusSquared) continue

                        val p = pos.add(x.toDouble(), y.toDouble(), z.toDouble())
                        positions.add(p)

                        instance.sendGroupedPacket(
                            ParticlePacket(
                                Particle.CAMPFIRE_SIGNAL_SMOKE,
                                p,
                                Pos(1.0, 1.0, 1.0),
                                0.05F,
                                1
                            )
                        )

                        instance.sendGroupedPacket(
                            ParticlePacket(
                                Particle.CAMPFIRE_COSY_SMOKE,
                                p,
                                Pos(1.0, 1.0, 1.0),
                                0.1F,
                                1
                            )
                        )
                    }
                }
            }

            // First pass: destroy all blocks
            positions.forEach { p ->
                instance.setBlock(p, Block.AIR)
            }

            // Second pass: place fire where appropriate (after destruction is complete)
            if (fire > 0) {
                positions.forEach { p ->
                    val blockBelow = instance.getBlock(p.add(0.0, -1.0, 0.0))
                    if (Random.nextDouble() < fire && blockBelow != Block.AIR && blockBelow.isSolid) {
                        instance.setBlock(p, Block.FIRE)
                    }
                }
            }
        }
    }
}