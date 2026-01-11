/**
 * Constants for diplomatic relations
 * (town, nation ally/enemy functions)
 */

package luna.nodes.constants

/**
 * Simple relationship groups:
 * Town - contains town residents
 * Ally - contains nation towns and other allies
 * Neutral - neutral towns, or players with no town
 * Enemy - enemy towns
 */
enum class DiplomaticRelationship {
    TOWN,
    NATION,
    ALLY,
    NEUTRAL,
    ENEMY,
}

// constants for setting enemy
val ErrorWarAlly = Exception("Cannot declare war against an ally")
val ErrorAlreadyEnemies = Exception("Already enemies")
val ErrorAlreadyAllies = Exception("Already allies")

// constants for adding/removing ally
val ErrorNotAllies = Exception("Not allies")
