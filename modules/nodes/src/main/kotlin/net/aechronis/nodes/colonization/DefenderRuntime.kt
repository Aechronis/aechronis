package net.aechronis.nodes.colonization

import net.aechronis.combat.objects.Gun
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.Attack
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.instance.Instance

internal data class AiDefender(
    val entity: EntityCreature,
    val brain: DefenderBrain,
    val motor: PlayerLikeGroundFollower,
    val navigation: DefenderNavigator,
    val weapon: DefenderWeapon,
    val blockBreaker: DefenderBlockBreaker,
    val guardPosition: Pos,
    var brainMemory: DefenderMemory = DefenderMemory(),
    var ready: Boolean = false,
    var lastDecisionAt: Long = 0,
    var lastDecision: DefenderDecision? = null,
)

internal data class AiDefenderSlot(
    val gun: Gun,
    var defender: AiDefender? = null,
    var respawnAtMillis: Long = 0,
    var spawnPending: Boolean = false,
    var generation: Int = 0,
)

internal class DefenseSession(
    val instance: Instance,
    val targetTown: Town,
    val slots: List<AiDefenderSlot>,
    val respawnDelayMillis: () -> Long,
    val spawnCenter: Pos,
) {
    val attacks: MutableSet<Attack> = linkedSetOf()
    val leasedAutomaticChunks: MutableSet<Coord> = mutableSetOf()
    val spawnPreparationChunks: MutableSet<Coord> = mutableSetOf()
    var spawnPreparationInFlight: Boolean = false
}
