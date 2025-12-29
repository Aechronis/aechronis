/**
 * Town outpost structure
 * Wrapper around territory with a name + spawnpoint
 */

package luna.nodes.objects

import net.minestom.server.coordinate.Pos

public data class TownOutpost(
    var name: String,
    val territory: TerritoryId,
    var spawn: Pos,
) {

    // serialize territory id + spawn location to json string
    public fun toJsonString(): String = "[${territory.toInt()}, ${spawn.x}, ${spawn.y}, ${spawn.z}]"
}
