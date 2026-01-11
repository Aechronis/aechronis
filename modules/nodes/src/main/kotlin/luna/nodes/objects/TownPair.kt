/**
 * Unordered pair of two towns
 *
 * If either town1 or town2 are equal, two TownPairs
 * must be equal.
 *
 */

package luna.nodes.objects

data class TownPair(
    val town1: Town,
    val town2: Town,
)