package net.aechronis.server.world

import net.minestom.server.MinecraftServer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.instance.block.rule.BlockPlacementRule
import net.minestom.server.instance.block.rule.BlockPlacementRule.PlacementState
import net.minestom.server.utils.Direction
import org.everbuild.blocksandstuff.blocks.placement.FacingPlacementRule
import org.everbuild.blocksandstuff.blocks.placement.LadderPlacementRule

/**
 * BlocksAndStuff writes vertical facings onto blocks that only have horizontal ones: shelves and
 * copper golem statues got `facing=up` when placed while looking up or down, and ladders got
 * `facing=top` when clicked onto a floor or ceiling. Both threw and the placement failed.
 */
internal object PlacementRuleFixes {
    fun register() {
        val manager = MinecraftServer.getBlockManager()
        Block.values().forEach { block ->
            when (val rule = manager.getBlockPlacementRule(block)) {
                is LadderPlacementRule -> manager.registerBlockPlacementRule(LadderRule(block, rule))
                is FacingPlacementRule ->
                    if (block.possibleStates().none { it.getProperty("facing") == "up" }) {
                        manager.registerBlockPlacementRule(HorizontalFacingRule(block))
                    }
            }
        }
    }

    private val HORIZONTAL = listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)

    /** The horizontal direction closest to where the player looks, null without a player. */
    private fun PlacementState.horizontalLook(): Direction? {
        val look = playerPosition()?.direction() ?: return null
        return HORIZONTAL.maxBy { it.vec().dot(look) }
    }

    private class HorizontalFacingRule(
        block: Block,
    ) : BlockPlacementRule(block) {
        override fun blockPlace(state: PlacementState): Block {
            // Faces the player, the same way BlocksAndStuff places these when looking straight ahead.
            val look = state.horizontalLook() ?: return state.block()
            return state.block().withProperty("facing", look.opposite().name.lowercase())
        }
    }

    private class LadderRule(
        block: Block,
        private val original: LadderPlacementRule,
    ) : BlockPlacementRule(block) {
        override fun blockPlace(state: PlacementState): Block? {
            val face = state.blockFace() ?: return null
            if (face != BlockFace.TOP && face != BlockFace.BOTTOM) return original.blockPlace(state)
            // Like vanilla, a ladder clicked onto a floor or ceiling goes on the wall the player faces.
            val look = state.horizontalLook() ?: return null
            val facing = BlockFace.fromDirection(look).oppositeFace
            val wall = state.instance().getBlock(state.placePosition().add(look.vec()))
            if (!wall.collisionShape().isFaceFull(facing)) return null
            return state.block().withProperty("facing", facing.name.lowercase())
        }

        override fun blockUpdate(updateState: UpdateState): Block = original.blockUpdate(updateState)
    }
}
