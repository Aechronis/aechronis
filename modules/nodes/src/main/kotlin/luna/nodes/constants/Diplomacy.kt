/**
 * Constants for diplomatic relations
 * War/enemy relationships are between nations only.
 * Alliances are between nations only.
 * Towns inherit diplomatic status from their nation.
 */

package luna.nodes.constants

/**
 * Simple relationship groups:
 * Town - contains town residents
 * Nation - towns in same nation
 * Ally - towns in allied nations
 * Neutral - neutral towns, or players with no town
 * Enemy - towns in enemy nations
 */
enum class DiplomaticRelationship {
    TOWN,
    NATION,
    ALLY,
    NEUTRAL,
    ENEMY,
}

// constants for setting enemy (nation-level only)
val ErrorWarAlly = Exception("Cannot declare war against an ally")
val ErrorAlreadyEnemies = Exception("Already enemies")
val ErrorAlreadyAllies = Exception("Already allies")
val ErrorWarSameNation = Exception("Cannot declare war on your own nation")

// constants for adding/removing ally
val ErrorNotAllies = Exception("Not allies")
