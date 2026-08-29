package net.aechronis.server.modules

import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.rule.BlockPlacementRule
import kotlin.test.Test
import kotlin.test.assertSame

class ModuleBlocksTest {
    @Test
    fun `replacement generations receive original placement fallback rather than bridge`() {
        val fallback = TestPlacementRule()
        val bridge = ModuleBlocks.PlacementRuleBridge(Block.OAK_SAPLING, fallback)
        val first = TestPlacementRule()
        val second = TestPlacementRule()

        assertSame(first, bridge.createReplacement { original -> assertSame(fallback, original).let { first } })
        assertSame(second, bridge.createReplacement { original -> assertSame(fallback, original).let { second } })
    }

    private class TestPlacementRule : BlockPlacementRule(Block.OAK_SAPLING) {
        override fun blockPlace(placementState: PlacementState): Block = placementState.block
    }
}
