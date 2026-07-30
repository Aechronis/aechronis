package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.KothListener
import net.aechronis.vanilla.objects.KothConfig
import net.aechronis.vanilla.objects.KothReward
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.timer.TaskSchedule
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

object Koth {
    internal data class ActiveKoth(
        val config: KothConfig,
        val startedAt: Long,
        val endsAt: Long,
        var capturer: UUID? = null,
        var captureStartedAt: Long? = null,
        val bossBars: MutableMap<UUID, BossBar> = mutableMapOf(),
        val visibleTo: MutableSet<UUID> = mutableSetOf(),
    )

    private val definitions = linkedMapOf<String, KothConfig>()
    private val schedules = linkedMapOf<String, List<LocalTime>>()
    private val scheduledRuns = mutableMapOf<String, LocalDateTime>()
    internal val active = linkedMapOf<String, ActiveKoth>()
    internal val deadPlayers = mutableSetOf<UUID>()

    fun init() {
        val timeStart = System.currentTimeMillis()
        loadConfiguration()
        KothListener.init()
        MinecraftServer
            .getSchedulerManager()
            .buildTask(::scheduledTick)
            .repeat(TaskSchedule.seconds(1))
            .schedule()
        println("├─ KOTH enabled in ${System.currentTimeMillis() - timeStart}ms")
    }

    fun configuredNames(): Set<String> = definitions.keys

    fun activeNames(): Set<String> = active.keys

    fun isActive(name: String): Boolean = name in active

    fun start(name: String): Boolean {
        val config = definitions[name] ?: return false
        if (active.containsKey(name)) return false

        val now = System.currentTimeMillis()
        active[name] =
            ActiveKoth(
                config = config,
                startedAt = now,
                endsAt = now + Vanilla.config.kothLengthSeconds * 1000,
            )
        broadcast(
            Component
                .text("KOTH started: ${config.name}", NamedTextColor.GOLD),
        )
        return true
    }

    fun stop(name: String): Boolean {
        if (name !in active) return false
        finish(name, null)
        return true
    }

    fun status(name: String): String? {
        val config = definitions[name] ?: return null
        val state = active[name] ?: return "${config.name}: inactive"
        val eventRemaining = remainingSeconds(state.endsAt, System.currentTimeMillis())
        val capturer = findPlayer(state.capturer)?.username ?: "nobody"
        return "${config.name}: active, ${eventRemaining}s left, capturing: $capturer"
    }

    internal fun tickAt(
        now: Long,
        dateTime: LocalDateTime,
    ) {
        startScheduled(dateTime)

        for ((name, state) in active.toMap()) {
            if (now >= state.endsAt) {
                finish(name, null)
                continue
            }

            if (state.capturer == null) {
                val player =
                    MinecraftServer
                        .getConnectionManager()
                        .onlinePlayers
                        .firstOrNull { isInside(state.config, it) }
                if (player != null) beginCapture(state, player.uuid, now)
            }

            val capturer = findPlayer(state.capturer)
            when {
                state.capturer != null && capturer == null -> {
                    resetCapture(state)
                }

                capturer != null && !isInside(state.config, capturer) -> {
                    resetCapture(state)
                }

                capturer != null && now - (state.captureStartedAt ?: now) >= state.config.captureSeconds * 1000 -> {
                    finish(name, capturer.uuid)
                }
            }
        }

        updateBossBars(now)
    }

    private fun scheduledTick() {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        tickAt(System.currentTimeMillis(), now.toLocalDateTime().withNano(0))
    }

    private fun loadConfiguration() {
        val config = Vanilla.config
        require(config.kothLengthSeconds > 0) { "KOTH length must be positive" }
        require(config.kothDisplayRadiusBlocks > 0) { "KOTH display radius must be positive" }
        require(
            config.koths
                .map { it.name }
                .toSet()
                .size == config.koths.size,
        ) { "KOTH names must be unique" }

        config.koths.forEach { it.validate() }
        definitions.clear()
        definitions.putAll(config.koths.associateBy { it.name })
        require(config.kothTimes.keys.all { it in definitions }) { "KOTH schedules contain an unknown KOTH" }
        schedules.clear()
        schedules.putAll(config.kothTimes)
    }

    private fun startScheduled(dateTime: LocalDateTime) {
        for ((name, times) in schedules) {
            for (time in times) {
                val scheduledAt = LocalDateTime.of(dateTime.toLocalDate(), time.withNano(0))
                if (dateTime != scheduledAt || scheduledRuns[name] == scheduledAt) continue
                scheduledRuns[name] = scheduledAt
                start(name)
            }
        }
    }

    internal fun beginCapture(
        state: ActiveKoth,
        player: UUID,
        now: Long,
    ) {
        state.capturer = player
        state.captureStartedAt = now
    }

    internal fun resetCaptures(player: UUID) {
        active.values.filter { it.capturer == player }.forEach(::resetCapture)
    }

    internal fun resetCapture(state: ActiveKoth) {
        state.capturer = null
        state.captureStartedAt = null
    }

    private fun finish(
        name: String,
        winner: UUID?,
    ) {
        val state = active.remove(name) ?: return
        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers.toList()
        state.bossBars.forEach { (uuid, bar) ->
            onlinePlayers.firstOrNull { it.uuid == uuid }?.hideBossBar(bar)
        }
        state.bossBars.clear()
        state.visibleTo.clear()

        val winnerPlayer = findPlayer(winner)
        if (winnerPlayer != null) {
            giveRewards(state.config, winnerPlayer)
            broadcast(
                Component.text(
                    "KOTH ${state.config.name} captured by ${winnerPlayer.username}!",
                    NamedTextColor.GREEN,
                ),
            )
        }
    }

    private fun giveRewards(
        config: KothConfig,
        player: Player,
    ) {
        for (reward in config.rewards) {
            when (reward) {
                is KothReward.Command -> {
                    MinecraftServer
                        .getCommandManager()
                        .executeServerCommand(
                            reward.command
                                .removePrefix("/")
                                .replace("%player%", player.username)
                                .replace("%koth%", config.name),
                        )
                }

                is KothReward.Item -> {
                    if (!player.inventory.addItemStack(reward.itemStack)) player.dropItem(reward.itemStack)
                }
            }
        }
    }

    internal fun updateBossBars(now: Long) {
        val players = MinecraftServer.getConnectionManager().onlinePlayers.toList()
        for (state in active.values) {
            val eventRemaining = (state.endsAt - now).coerceAtLeast(0)
            val eventDuration = (state.endsAt - state.startedAt).coerceAtLeast(1)
            val capturer = findPlayer(state.capturer)
            val captureRemaining =
                capturer?.let {
                    (state.config.captureSeconds * 1000 - (now - (state.captureStartedAt ?: now))).coerceAtLeast(0)
                }
            val captureDuration = (state.config.captureSeconds * 1000).coerceAtLeast(1)

            for (player in players) {
                val nearby =
                    player.instance === state.config.instance &&
                        player.position.distanceSquared(state.config.zone.center) <=
                        Vanilla.config.kothDisplayRadiusBlocks * Vanilla.config.kothDisplayRadiusBlocks
                val bar =
                    state.bossBars.getOrPut(player.uuid) {
                        BossBar.bossBar(
                            Component.empty(),
                            1f,
                            BossBar.Color.YELLOW,
                            BossBar.Overlay.PROGRESS,
                        )
                    }

                if (!nearby) {
                    if (state.visibleTo.remove(player.uuid)) player.hideBossBar(bar)
                    continue
                }

                if (state.capturer == player.uuid && captureRemaining != null) {
                    bar.name(Component.text("KOTH ${state.config.name} | Capture: ${formatTime(captureRemaining)}"))
                    bar.progress((captureRemaining.toFloat() / captureDuration).coerceIn(0f, 1f))
                } else {
                    bar.name(Component.text("KOTH ${state.config.name} | Time left: ${formatTime(eventRemaining)}"))
                    bar.progress((eventRemaining.toFloat() / eventDuration).coerceIn(0f, 1f))
                }

                if (state.visibleTo.add(player.uuid)) player.showBossBar(bar)
            }
        }
    }

    internal fun isInside(
        config: KothConfig,
        player: Player,
        position: Pos = player.position,
    ): Boolean =
        player.uuid !in deadPlayers &&
            player.gameMode != GameMode.SPECTATOR &&
            player.instance === config.instance &&
            config.zone.contains(position)

    private fun findPlayer(uuid: UUID?): Player? =
        uuid?.let { target ->
            MinecraftServer.getConnectionManager().onlinePlayers.firstOrNull { it.uuid == target }
        }

    private fun remainingSeconds(
        end: Long,
        now: Long,
    ): Long = ((end - now).coerceAtLeast(0) + 999) / 1000

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = ((milliseconds.coerceAtLeast(0) + 999) / 1000)
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun broadcast(message: Component) {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.sendMessage(message) }
    }
}
