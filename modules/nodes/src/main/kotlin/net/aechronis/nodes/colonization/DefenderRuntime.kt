package net.aechronis.nodes.colonization

import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Throwable
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.Attack
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.instance.Instance

internal data class AiDefender(
    val entity: EntityCreature,
    val brain: DefenderBrain,
    val motor: PlayerLikeGroundFollower,
    val navigation: DefenderNavigator,
    val weapon: DefenderWeapon?,
    val throwable: DefenderThrowable?,
    val blockBreaker: DefenderBlockBreaker,
    val guardPosition: Pos,
    var brainMemory: DefenderMemory = DefenderMemory(),
    var ready: Boolean = false,
    var lastDecisionAt: Long = 0,
    var lastDecision: DefenderDecision? = null,
    var flagRunner: Boolean = false,
    var previousPosition: Pos? = null,
    var recentSpeed: Float = 0F,
) {
    // roughly the blocks moved over the last second, the same measure that widens a player's spread
    fun trackSpeed() {
        val position = entity.position
        recentSpeed = recentSpeed * SPEED_RETAINED_PER_TICK + (previousPosition?.distance(position)?.toFloat() ?: 0F)
        previousPosition = position
    }

    private companion object {
        const val SPEED_RETAINED_PER_TICK = 0.95F
    }
}

internal data class AiDefenderSlot(
    val gunName: String?,
    val throwableName: String? = null,
    var defender: AiDefender? = null,
    var respawnAtMillis: Long = 0,
    var spawnPending: Boolean = false,
    var generation: Int = 0,
) {
    val gun: Gun? get() = gunName?.let { Item.getFromName(it) as? Gun }?.takeIf { it.maxAmmo > 0 }
    val throwable: Throwable? get() = throwableName?.let { Item.getFromName(it) as? Throwable }
    val armed: Boolean get() = gun != null || throwable != null
}

internal class DefenseSession(
    val instance: Instance,
    val targetTown: Town,
    val slots: List<AiDefenderSlot>,
    val respawnDelayMillis: () -> Long,
    val spawnCenter: Pos,
) {
    val attacks: MutableSet<Attack> = linkedSetOf()
    val placedTerrainBlocks: MutableMap<Attack, Int> = mutableMapOf()
    val terrainBlockLimits: MutableMap<Attack, Int> = mutableMapOf()
    val placedTerrainPositions: MutableSet<BlockVec> = mutableSetOf()
    val staircases: MutableMap<Attack, DefenderStaircase> = mutableMapOf()
    val leasedAutomaticChunks: MutableSet<Coord> = mutableSetOf()
    val spawnPreparationChunks: MutableSet<Coord> = mutableSetOf()
    var spawnPreparationInFlight: Boolean = false
}
