package net.aechronis.nodes.colonization

import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Throwable
import kotlin.random.Random

internal class DefenderThrowable(
    throwable: Throwable,
    private val random: Random = Random.Default,
) {
    private val throwableName = throwable.name
    val throwable: Throwable? get() = Item.getFromName(throwableName) as? Throwable
    private var consumablesRemaining: Int = CONSUMABLES_PER_LIFE
    private var nextThrowAtMillis: Long = 0

    fun canThrow(nowMillis: Long): Boolean {
        val throwable = throwable ?: return false
        return nowMillis >= nextThrowAtMillis && (throwable.throwCooldownMillis != null || consumablesRemaining > 0)
    }

    fun recordThrow(nowMillis: Long) {
        require(canThrow(nowMillis)) { "The defender throwable is not ready to throw" }
        val cooldown = checkNotNull(throwable).throwCooldownMillis
        if (cooldown == null) consumablesRemaining -= 1
        val pause = random.nextLong(THROW_PAUSE_MILLIS.first, THROW_PAUSE_MILLIS.last)
        nextThrowAtMillis = nowMillis + (cooldown ?: CONSUMABLE_COOLDOWN_MILLIS) + pause
    }

    private companion object {
        const val CONSUMABLES_PER_LIFE = 2
        const val CONSUMABLE_COOLDOWN_MILLIS = 8_000L
        val THROW_PAUSE_MILLIS: LongRange = 1_000L..3_000L
    }
}
