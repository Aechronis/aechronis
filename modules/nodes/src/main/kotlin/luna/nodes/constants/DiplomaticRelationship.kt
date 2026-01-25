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
