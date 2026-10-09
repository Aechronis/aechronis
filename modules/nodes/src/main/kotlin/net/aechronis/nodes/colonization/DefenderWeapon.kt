package net.aechronis.nodes.colonization

import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import kotlin.random.Random

internal class DefenderWeapon(
    gun: Gun,
    private val random: Random = Random.Default,
) {
    private val gunName = gun.name
    val gun: Gun? get() = (Item.getFromName(gunName) as? Gun)?.takeIf { it.maxAmmo > 0 }
    private var ammunition: Int = gun.maxAmmo
    private var reloadFinishedAtMillis: Long = 0
    private var shotsRemainingInBurst: Int = 0
    private var nextFireAtMillis: Long = 0

    val reloadPending: Boolean get() = gun == null || ammunition == 0 || reloadFinishedAtMillis != 0L

    fun updateReload(nowMillis: Long) {
        require(nowMillis >= 0)
        val gun = gun ?: return
        ammunition = ammunition.coerceAtMost(gun.maxAmmo)
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

    fun canFire(nowMillis: Long): Boolean = gun != null &&
        ammunition > 0 &&
        reloadFinishedAtMillis == 0L &&
        nowMillis >= nextFireAtMillis

    fun recordShot(nowMillis: Long) {
        require(canFire(nowMillis)) { "The defender weapon is not ready to fire" }
        val gun = checkNotNull(gun)
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
