package net.aechronis.vanilla.managers

import net.minestom.server.MinecraftServer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockTags
import net.minestom.server.instance.block.rule.BlockPlacementRule
import net.minestom.server.registry.TagKey

internal class SaplingPlacementRule(
    block: Block,
    private val fallback: BlockPlacementRule?,
) : BlockPlacementRule(block) {
    override fun blockPlace(placementState: PlacementState): Block? {
        val support = placementState.instance.getBlock(placementState.placePosition.add(0.0, -1.0, 0.0))
        return placementState.block.takeIf { canPlantOn(it, support) }
    }

    override fun blockUpdate(updateState: UpdateState): Block {
        val support = updateState.instance.getBlock(updateState.blockPosition.add(0.0, -1.0, 0.0))
        if (canPlantOn(updateState.currentBlock, support)) return updateState.currentBlock

        // the existing rule owns drop spawning; only replace its incomplete support check
        return fallback?.blockUpdate(updateState) ?: Block.AIR
    }

    override fun isSelfReplaceable(replacement: Replacement): Boolean = fallback?.isSelfReplaceable(replacement) ?: false

    override fun maxUpdateDistance(): Int = fallback?.maxUpdateDistance() ?: super.maxUpdateDistance()

    companion object {
        internal fun canPlantOn(
            sapling: Block,
            support: Block,
        ): Boolean {
            val supportTag =
                when {
                    sapling.compare(Block.MANGROVE_PROPAGULE) -> BlockTags.SUPPORTS_MANGROVE_PROPAGULE
                    sapling.compare(Block.AZALEA) || sapling.compare(Block.FLOWERING_AZALEA) -> BlockTags.SUPPORTS_AZALEA
                    else -> BlockTags.SUPPORTS_VEGETATION
                }
            return blockIsInTag(support, supportTag)
        }

        private fun blockIsInTag(
            block: Block,
            tag: TagKey<Block>,
        ): Boolean =
            MinecraftServer
                .process()
                .blocks()
                .getTag(tag)
                ?.contains(block) == true
    }
}
