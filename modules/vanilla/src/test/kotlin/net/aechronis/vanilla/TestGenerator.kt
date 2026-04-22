package net.aechronis.vanilla

import net.minestom.server.instance.block.Block
import net.minestom.server.instance.generator.GenerationUnit
import net.minestom.server.instance.generator.Generator

class TestGenerator : Generator {
    // stone from y=0 to y=59
    override fun generate(unit: GenerationUnit) {
        val start = unit.absoluteStart()
        val end = unit.absoluteEnd()
        for (x in start.blockX()..<end.blockX()) {
            for (z in start.blockZ()..<end.blockZ()) {
                for (y in 0..59) {
                    unit.modifier().setBlock(x, y, z, Block.STONE)
                }
            }
        }
    }
}
