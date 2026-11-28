/**
 * All blocks that have an interactive event that must be
 * manually cancelled.
 *
 * -> When player has fence in hand, must allow interact event
 * -> Force cancel when target block is in this list or one of the tags below
 * -> Tags cover every wood and copper variant, so new block types are protected automatically
 */

package net.aechronis.nodes.constants

import net.minestom.server.MinecraftServer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockTags

/** Blocks that are not covered by a tag. */
private val INTERACTIVE_BLOCKS: Set<Block> = setOf(
    Block.CHEST,
    Block.TRAPPED_CHEST,
    Block.BARREL,
    Block.FURNACE,
    Block.BLAST_FURNACE,
    Block.SMOKER,
    Block.REPEATER,
    Block.COMPARATOR,
    Block.LEVER,
    Block.DAYLIGHT_DETECTOR,
    Block.DROPPER,
    Block.DISPENSER,
    Block.COMPOSTER,
    Block.BEACON,
    Block.JUKEBOX,
)

private val INTERACTIVE_TAGS = listOf(
    BlockTags.SHULKER_BOXES,
    BlockTags.BUTTONS,
    BlockTags.DOORS,
    BlockTags.TRAPDOORS,
    BlockTags.FENCE_GATES,
)

fun isInteractiveBlock(block: Block): Boolean {
    if (INTERACTIVE_BLOCKS.any { block.compare(it) }) return true
    val tags = MinecraftServer.process().blocks()
    return INTERACTIVE_TAGS.any { tags.getTag(it)?.contains(block) == true }
}
