/**
 * Cache broken block locations used for hidden ore
 * Used to avoid exploits of placing and re-breaking blocks
 * to get ore.
 *
 * Object is wrapper around hashset, client should only add
 * blocks into cache (removing handled internally), and use
 * contains to check if block exists
 *
 * Size input: number of blocks to cache before overwriting
 */

package luna.nodes.objects

import net.minestom.server.coordinate.BlockVec
import java.util.Collections

public class OreBlockCache(val maxSize: Int) {
    private val cache: MutableSet<BlockVec> = Collections.newSetFromMap(object : LinkedHashMap<BlockVec, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BlockVec, Boolean>): Boolean = this.size > maxSize
    })

    public fun add(block: BlockVec) {
        this.cache.add(block)
    }

    public fun contains(block: BlockVec): Boolean = cache.contains(block)
}
