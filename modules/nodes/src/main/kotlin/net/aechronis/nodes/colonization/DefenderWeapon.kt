package net.aechronis.nodes.colonization

import net.aechronis.combat.objects.Gun
import kotlin.random.Random

internal class DefenderWeapon(
    val gun: Gun,
    private val random: Random = Random.Default,
) {
    private var ammunition: Int = gun.maxAmmo
    private var reloadFinishedAtMillis: Long = 0
    private var shotsRemainingInBurst: Int = 0
    private var nextFireAtMillis: Long = 0

    val reloadPending: Boolean get() = ammunition == 0 || reloadFinishedAtMillis != 0L

    fun updateReload(nowMillis: Long) {
        require(nowMillis >= 0)
        require(gun.maxAmmo > 0)
        if (ammunition > 0) return

        if (reloadFinishedAtMillis == 0L) {
            reloadFinishedAtMillis = safeAdd(nowMillis, gun.reloadTime.coerceAtLeast(1))
            return
        }
        if (nowMillis >= reloadFinishedAtMillis) {
            ammunition = gun.maxAmmo
            reloadFinishedAtMillis = 0
        }
    }

    fun canFire(nowMillis: Long): Boolean = ammunition > 0 &&
        reloadFinishedAtMillis == 0L &&
        nowMillis >= nextFireAtMillis

    fun recordShot(nowMillis: Long) {
        require(canFire(nowMillis)) { "The defender weapon is not ready to fire" }
        val burstRemaining = if (shotsRemainingInBurst > 0) {
            shotsRemainingInBurst
        } else {
            random.nextInt(BURST_SHOTS.first, BURST_SHOTS.last)
        }
        val shotsAfterThis = burstRemaining - 1
        val nextDelay = if (shotsAfterThis == 0) {
            safeAdd(gun.cooldown, random.nextLong(BURST_PAUSE_MILLIS.first, BURST_PAUSE_MILLIS.last))
        } else {
            gun.cooldown
        }
        ammunition -= 1
        reloadFinishedAtMillis = if (ammunition == 0) {
            safeAdd(nowMillis, gun.reloadTime.coerceAtLeast(1))
        } else {
            0
        }
        shotsRemainingInBurst = shotsAfterThis
        nextFireAtMillis = safeAdd(nowMillis, nextDelay)
    }

    private fun safeAdd(
        left: Long,
        right: Long,
    ): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        val BURST_SHOTS: IntRange = 2..4
        val BURST_PAUSE_MILLIS: LongRange = 240L..520L
    }
}
