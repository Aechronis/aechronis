package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.war.Attack
import net.aechronis.nodes.war.FlagWar
import net.minestom.server.coordinate.Pos
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Owns active session identities and the automatic chunk leases used by their defenders. */
internal class DefenseSessions {
    private val sessions = HashMap<UUID, DefenseSession>()
    private var chunkLeases = AutomaticChunkLeaseManager()

    fun find(targetTownId: UUID): DefenseSession? = sessions[targetTownId]

    fun all(): List<DefenseSession> = sessions.values.toList()

    fun add(session: DefenseSession) {
        sessions[session.targetTown.uuid] = session
    }

    fun isActive(session: DefenseSession): Boolean = sessions[session.targetTown.uuid] === session

    fun loadChunks(
        session: DefenseSession,
        chunks: Collection<Coord>,
    ): CompletableFuture<Void> = chunkLeases.loadChunks(session.instance, session, chunks)

    fun reconcileChunks(session: DefenseSession) = chunkLeases.reconcile(session)

    fun end(session: DefenseSession) {
        // Invalidate identity before teardown so queued spawn/path callbacks cannot revive it.
        if (!sessions.remove(session.targetTown.uuid, session)) return
        session.slots.forEach { slot ->
            slot.generation += 1
            slot.spawnPending = false
            slot.defender?.let { defender ->
                defender.blockBreaker.stop(session.instance)
                defender.navigation.cancel()
                if (!defender.entity.isRemoved) defender.entity.remove()
            }
            slot.defender = null
        }
        session.spawnPreparationChunks.clear()
        chunkLeases.release(session)
    }

    fun clear() = all().forEach(::end)

    fun prepareForShutdown(
        timeout: Long,
        unit: TimeUnit,
    ) = chunkLeases.prepareForShutdown(timeout, unit)

    fun acceptsAutomaticChunkLeases(): Boolean = chunkLeases.isOpen()

    /** Called only after pending loads have drained and every session has ended. */
    fun resetChunkLeases() {
        chunkLeases.shutdown()
        chunkLeases = AutomaticChunkLeaseManager()
    }

    fun shutdown() = chunkLeases.shutdown()
}

internal fun activeDefenders(session: DefenseSession): List<AiDefender> = session.slots
    .mapNotNull(AiDefenderSlot::defender)

internal fun isAttackActive(attack: Attack): Boolean = FlagWar.chunkToAttacker[attack.coord] === attack &&
    FlagWar.blockToAttacker[attack.flagBlock] === attack

internal fun closestActiveAttack(
    session: DefenseSession,
    position: Pos,
): Attack? = session.attacks
    .asSequence()
    .filter(::isAttackActive)
    .minByOrNull { attack -> position.distanceSquared(attack.flagBlock.add(0.5, 0.5, 0.5)) }
