package luna.nodes.nodes

import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.GenerationUnit
import net.minestom.server.instance.generator.Generator
import java.util.concurrent.ThreadLocalRandom

class TestGenerator : Generator {

    // random stone + chests from y=0 to y=59
    override fun generate(unit: GenerationUnit) {
        val start = unit.absoluteStart()
        val end = unit.absoluteEnd()
        for (x in start.blockX()..<end.blockX()) {
            for (z in start.blockZ()..<end.blockZ()) {
                for (y in 0..59) {
                    if (ThreadLocalRandom.current().nextInt(100) == 1) {
                        unit.modifier().setBlock(x, y, z, Block.CHEST)
                    } else if (ThreadLocalRandom.current().nextInt(100) == 1) {
                        unit.modifier().setBlock(x, y, z, Block.OAK_TRAPDOOR)
                    } else {
                        unit.modifier().setBlock(x, y, z, Block.STONE)
                    }
                }
            }
        }
    }
}
