package net.aechronis.nodes.colonization

import net.aechronis.combat.utils.Ray
import net.aechronis.nodes.war.Attack
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityCreature
import net.minestom.server.instance.Instance

internal const val FLAG_REACH = 3

internal fun hasClearFlagReach(
    defender: EntityCreature,
    flagPosition: Pos,
    instance: Instance,
): Boolean = hasClearFlagReach(defender.position.add(0.0, defender.eyeHeight, 0.0), flagPosition, instance)

internal fun hasClearFlagReach(
    origin: Pos,
    flagPosition: Pos,
    instance: Instance,
): Boolean {
    val delta = Vec(flagPosition.x - origin.x, flagPosition.y - origin.y, flagPosition.z - origin.z)
    val distance = delta.length()
    val reachVector = if (distance <= 0.75) Vec.ZERO else delta.normalize().mul(distance - 0.75)
    return Ray(origin, reachVector).firstBlock(instance) == null
}

internal fun hasClearShot(
    defender: EntityCreature,
    targetPosition: Pos,
    instance: Instance,
): Boolean {
    val origin = defender.position.add(0.0, defender.eyeHeight, 0.0)
    val vector = Vec(
        targetPosition.x - origin.x,
        targetPosition.y - origin.y,
        targetPosition.z - origin.z,
    )
    return Ray(origin, vector).firstBlock(instance) == null
}

internal fun DefenderPoint.toPos(): Pos = Pos(x, y, z)

internal fun Pos.toDefenderPoint(): DefenderPoint = DefenderPoint(x, y, z)

internal fun campaignObjective(
    defender: AiDefender,
    attack: Attack?,
): Pos = attack?.flagBlock?.add(0.5, 0.5, 0.5)?.asPos() ?: defender.guardPosition
