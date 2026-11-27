package net.aechronis.nodes.objects

import net.aechronis.combat.managers.CombatTags
import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.war.FlagWar
import net.aechronis.server.Server
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TownFly {
    fun isAllowed(
        town: Town?,
        territory: Territory?,
    ): Boolean {
        val territoryTown = territory?.town ?: return false
        if (territoryTown === town) return true

        val nation = town?.nation
        return nation != null && territoryTown.nation === nation
    }

    // Players can't attack others while flying or for a while after (Nodes.config.townFlyPvpLockTime);
    // getting hit ends flight and shortens the wait (townFlyPvpLockAfterHitTime).

    // Lock expiry per player; Long.MAX_VALUE while flying. In memory, so it resets on server restart.
    private val pvpLockedUntil = ConcurrentHashMap<UUID, Long>()

    // Bossbars shown to locked players.
    private val bars = ConcurrentHashMap<UUID, BossBar>()
    private var barTask: Task? = null

    // admins in creative/spectator are never restricted
    private fun isExempt(player: Player) = player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR

    fun enable(player: Player) {
        player.isAllowFlying = true
        pvpLockedUntil[player.uuid] = Long.MAX_VALUE
    }

    fun disable(player: Player) {
        player.isFlying = false
        player.isAllowFlying = false
        player.addEffect(Potion(PotionEffect.SLOW_FALLING, 0, 100))
        pvpLockedUntil[player.uuid] = System.currentTimeMillis() + Nodes.config.townFlyPvpLockTime
    }

    /** Milliseconds until the player may attack again: 0 if not locked, [Long.MAX_VALUE] while flying. */
    fun pvpLockRemainingMs(player: Player): Long {
        if (isExempt(player)) return 0
        // no entry: flight survived a restart (or predates the lock), so treat it as flying
        val until = pvpLockedUntil[player.uuid] ?: return if (player.isAllowFlying) Long.MAX_VALUE else 0
        if (until != Long.MAX_VALUE) return (until - System.currentTimeMillis()).coerceAtLeast(0)
        if (player.isAllowFlying) return Long.MAX_VALUE

        // flight was cleared without disable() (e.g. duel prep): start the post-flight lock now
        val lockTime = Nodes.config.townFlyPvpLockTime
        pvpLockedUntil.replace(player.uuid, Long.MAX_VALUE, System.currentTimeMillis() + lockTime)
        return lockTime
    }

    fun isPvpLocked(player: Player): Boolean = pvpLockRemainingMs(player) > 0

    /** Call when a player takes player damage: ends their town flight and caps the remaining lock. */
    fun onPvpHit(victim: Player) {
        if (victim.isAllowFlying && !isExempt(victim)) {
            disable(victim)
            Message.print(victim, "You were hit, disabling flight")
        }
        val capped = System.currentTimeMillis() + Nodes.config.townFlyPvpLockAfterHitTime
        pvpLockedUntil.computeIfPresent(victim.uuid) { _, until -> minOf(until, capped) }
    }

    fun disableDuringWar(player: Player): Boolean {
        if (!FlagWar.enabled || isExempt(player)) return false
        if (!player.isAllowFlying && !player.isFlying) return false
        disable(player)
        return true
    }

    fun start() {
        barTask = ModuleScheduler.buildTask(::tick).repeat(TaskSchedule.tick(20)).schedule()
    }

    fun stop() {
        barTask?.cancel()
        barTask = null
        val connections = MinecraftServer.getConnectionManager()
        bars.forEach { (uuid, bar) -> connections.getOnlinePlayerByUuid(uuid)?.hideBossBar(bar) }
        bars.clear()
    }

    private fun tick() {
        val connections = MinecraftServer.getConnectionManager()

        // drop bars of players who left, and expired locks (never flying: hits end flight, so only
        // an active flight holds Long.MAX_VALUE)
        bars.keys.removeIf { connections.getOnlinePlayerByUuid(it) == null }
        val now = System.currentTimeMillis()
        pvpLockedUntil.values.removeIf { it < now }

        for (player in connections.onlinePlayers) {
            // no flying in combat (the command also refuses to enable it)
            if (player.isAllowFlying && !isExempt(player) && CombatTags.isInCombat(player)) {
                disable(player)
                Message.print(player, "You are in combat, disabling flight")
            }

            // duel arenas allow pvp, so the lock doesn't apply there
            val remaining = if (player.instance === Server.instance) pvpLockRemainingMs(player) else 0
            if (remaining <= 0) {
                bars.remove(player.uuid)?.let(player::hideBossBar)
                continue
            }
            showBar(player, remaining)
        }
    }

    private fun showBar(
        player: Player,
        remaining: Long,
    ) {
        val flying = remaining == Long.MAX_VALUE
        val title = if (flying) {
            Component.text("PvP disabled while flying", NamedTextColor.YELLOW)
        } else {
            val seconds = (remaining + 999) / 1000
            Component.text("PvP disabled for %d:%02d".format(seconds / 60, seconds % 60), NamedTextColor.YELLOW)
        }
        val progress = if (flying) 1f else (remaining.toFloat() / Nodes.config.townFlyPvpLockTime).coerceIn(0f, 1f)

        val bar = bars[player.uuid]
        if (bar == null) {
            val created = BossBar.bossBar(title, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
            bars[player.uuid] = created
            player.showBossBar(created)
        } else {
            bar.name(title).progress(progress)
        }
    }
}
