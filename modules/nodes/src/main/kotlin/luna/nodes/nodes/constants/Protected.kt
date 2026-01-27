/**
 * List of town protectable blocks for Chests permissions
 * and for trusted player permissions
 *
 * TODO: include shulker boxes?
 */

package luna.nodes.nodes.constants

import net.minestom.server.instance.block.Block

val PROTECTED_BLOCKS: List<Block> = listOf(
    Block.CHEST,
    Block.TRAPPED_CHEST,
    Block.FURNACE,

    // 1.16
    Block.BARREL, // 1.16
    Block.BLAST_FURNACE,
)
