package net.aechronis.combat.utils

import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import java.util.concurrent.ConcurrentHashMap

internal data class HistoricalHitbox(
    val minimum: Vec,
    val maximum: Vec,
)

internal data class HitboxSnapshot(
    val capturedAtNanos: Long,
    val hitbox: HistoricalHitbox,
)

private data class PlayerHistory(
    val instance: Instance,
    // A history is replaced as a whole so readers can interpolate without racing a writer.
    val snapshots: List<HitboxSnapshot>,
)

object LagCompensation {
    private const val HISTORY_MILLIS = 600L
    private const val LATENCY_SMOOTHING_ALPHA = 0.1
    private const val MAX_HISTORY_SNAPSHOTS = 32
    private const val NANOS_PER_MILLI = 1_000_000L

    private val clientInterpolationMillis = doubleProperty("clientInterpolationMillis", 75.0, 0.0, 300.0)
    private val packetQueueEstimateMillis = doubleProperty("packetQueueEstimateMillis", 25.0, 0.0, 100.0)
    private val maxRewindMillis = doubleProperty("maxRewindMillis", 200.0, 0.0, 500.0)

    private val playerHistories = ConcurrentHashMap<Player, PlayerHistory>()
    private val smoothedLatencies = ConcurrentHashMap<Player, Double>()

    fun recordPlayer(
        player: Player,
        capturedAtNanos: Long = System.nanoTime(),
    ) {
        updateLatency(player)
        if (player.isDead) return resetHistory(player)

        val instance = player.instance ?: return resetHistory(player)
        val position = player.position
        val boundingBox = player.boundingBox
        val minimum = boundingBox.relativeStart().add(position)
        val maximum = boundingBox.relativeEnd().add(position)
        val snapshot =
            HitboxSnapshot(
                capturedAtNanos,
                HistoricalHitbox(
                    Vec(minimum.x(), minimum.y(), minimum.z()),
                    Vec(maximum.x(), maximum.y(), maximum.z()),
                ),
            )

        playerHistories.compute(player) { _, previous ->
            val existingSnapshots = previous?.takeIf { it.instance === instance }?.snapshots.orEmpty()
            val updatedSnapshots =
                buildList(existingSnapshots.size + 1) {
                    addAll(existingSnapshots)
                    if (lastOrNull()?.capturedAtNanos == capturedAtNanos) removeAt(lastIndex)
                    add(snapshot)
                }
            val cutoff = capturedAtNanos - HISTORY_MILLIS * NANOS_PER_MILLI
            val firstRetained = updatedSnapshots.indexOfFirst { it.capturedAtNanos >= cutoff }
            val retainedSnapshots =
                (if (firstRetained == -1) emptyList() else updatedSnapshots.subList(firstRetained, updatedSnapshots.size))
                    .takeLast(MAX_HISTORY_SNAPSHOTS)

            PlayerHistory(instance, retainedSnapshots)
        }
    }

    fun firstEntityHit(
        ray: Ray,
        shooter: Player,
        instance: Instance,
        firedAtNanos: Long = System.nanoTime(),
    ): Ray.Hit<LivingEntity>? {
        val targetTime = firedAtNanos - (rewindMillis(shooter) * NANOS_PER_MILLI).toLong()
        var best: Ray.Hit<LivingEntity>? = null

        for (target in instance.entities.filterIsInstance<LivingEntity>()) {
            if (target === shooter || target.isDead) continue

            val historicalHitbox =
                if (target is Player) {
                    playerHistories[target]
                        ?.takeIf { it.instance === instance }
                        ?.snapshots
                        ?.let { interpolateHitbox(it, targetTime) }
                } else {
                    null
                }

            val hit =
                if (historicalHitbox != null) {
                    ray.hitBox(historicalHitbox.minimum, historicalHitbox.maximum, target)
                } else {
                    ray.hitEntity(target)
                }

            if (hit != null && (best == null || hit.t < best.t)) best = hit
        }

        return best
    }

    fun resetHistory(player: Player) {
        playerHistories.remove(player)
    }

    fun removePlayer(player: Player) {
        playerHistories.remove(player)
        smoothedLatencies.remove(player)
    }

    private fun updateLatency(player: Player) {
        val sample = player.latency.coerceAtLeast(0).toDouble()
        smoothedLatencies.compute(player) { _, previous ->
            if (previous == null) sample else previous + LATENCY_SMOOTHING_ALPHA * (sample - previous)
        }
    }

    private fun rewindMillis(player: Player): Double = fallbackRewindMillis(player)

    private fun smoothedRttMillis(player: Player): Double = smoothedLatencies[player] ?: player.latency.coerceAtLeast(0).toDouble()

    private fun fallbackRewindMillis(player: Player): Double = calculateRewindMillis(smoothedRttMillis(player))

    internal fun calculateRewindMillis(smoothedRttMillis: Double): Double =
        (smoothedRttMillis + clientInterpolationMillis + packetQueueEstimateMillis)
            .coerceIn(0.0, maxRewindMillis)

    private fun doubleProperty(
        suffix: String,
        default: Double,
        minimum: Double,
        maximum: Double,
    ): Double {
        val configuredValue = System.getProperty("aechronis.combat.lagCompensation.$suffix")
        return configuredValue
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in minimum..maximum }
            ?: default
    }
}

internal fun interpolateHitbox(
    snapshots: Collection<HitboxSnapshot>,
    targetTimeNanos: Long,
): HistoricalHitbox? {
    val iterator = snapshots.iterator()
    if (!iterator.hasNext()) return null

    var previous = iterator.next()
    if (targetTimeNanos < previous.capturedAtNanos) return null

    while (iterator.hasNext()) {
        val current = iterator.next()
        if (targetTimeNanos <= current.capturedAtNanos) {
            val duration = current.capturedAtNanos - previous.capturedAtNanos
            if (duration <= 0L) return current.hitbox

            val progress = (targetTimeNanos - previous.capturedAtNanos).toDouble() / duration.toDouble()
            return HistoricalHitbox(
                interpolate(previous.hitbox.minimum, current.hitbox.minimum, progress),
                interpolate(previous.hitbox.maximum, current.hitbox.maximum, progress),
            )
        }
        previous = current
    }

    return previous.hitbox
}

private fun interpolate(
    from: Vec,
    to: Vec,
    progress: Double,
): Vec = from.add(to.sub(from).mul(progress))
