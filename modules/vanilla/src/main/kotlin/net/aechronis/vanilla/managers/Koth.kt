package net.aechronis.vanilla.managers

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.aechronis.vanilla.listeners.KothListener
import net.aechronis.vanilla.objects.KothZone
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** Command-configured King of the Hill events persisted in vanilla/koth.json. */
object Koth {
    internal data class SavedPosition(
        val x: Int,
        val y: Int,
        val z: Int,
    )

    internal data class SavedKoth(
        val name: String,
        var world: String? = null,
        var cornerOne: SavedPosition? = null,
        var cornerTwo: SavedPosition? = null,
        val captureSeconds: Long,
        val eventSeconds: Long,
        val displayRadiusBlocks: Double,
        val schedules: MutableList<String> = mutableListOf(),
        val rewardCommands: MutableList<String> = mutableListOf(),
    )

    internal data class Definition(
        val saved: SavedKoth,
        val instance: Instance,
        val zone: KothZone,
    )

    internal data class ActiveKoth(
        val definition: Definition,
        val startedAt: Long,
        val endsAt: Long,
        var capturer: UUID? = null,
        var captureStartedAt: Long? = null,
        val bossBars: MutableMap<UUID, BossBar> = mutableMapOf(),
        val visibleTo: MutableSet<UUID> = mutableSetOf(),
    )

    private val definitions = linkedMapOf<String, SavedKoth>()
    private val scheduledRuns = mutableMapOf<String, LocalDateTime>()
    internal val active = linkedMapOf<String, ActiveKoth>()
    internal val deadPlayers = mutableSetOf<UUID>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var file: Path

    fun init(path: Path) {
        val timeStart = System.currentTimeMillis()
        file = path
        Files.createDirectories(path.parent)
        load()
        KothListener.init()
        MinecraftServer
            .getSchedulerManager()
            .buildTask(::scheduledTick)
            .repeat(TaskSchedule.seconds(1))
            .schedule()
        println("├─ KOTH enabled in ${System.currentTimeMillis() - timeStart}ms (${definitions.size} configured)")
    }

    fun saveAll() = save()

    fun configuredNames(): Set<String> = definitions.keys

    fun activeNames(): Set<String> = active.keys

    fun isActive(name: String): Boolean = name in active

    fun add(
        name: String,
        captureSeconds: Long,
        eventSeconds: Long,
        displayRadiusBlocks: Double,
    ): Boolean {
        if (
            name.isBlank() ||
            name in definitions ||
            captureSeconds <= 0 ||
            eventSeconds <= 0 ||
            displayRadiusBlocks <= 0
        ) {
            return false
        }
        definitions[name] =
            SavedKoth(name, captureSeconds = captureSeconds, eventSeconds = eventSeconds, displayRadiusBlocks = displayRadiusBlocks)
        save()
        return true
    }

    fun remove(name: String): Boolean {
        if (name !in definitions || name in active) return false
        definitions.remove(name)
        scheduledRuns.remove(name)
        save()
        return true
    }

    fun setCorner(
        name: String,
        player: Player,
        first: Boolean,
    ): Boolean {
        val saved = definitions[name] ?: return false
        val instance = player.instance ?: return false
        val world = instance.getDimensionName()
        if (saved.world != null && saved.world != world) return false
        saved.world = world
        val position = player.position
        val corner = SavedPosition(position.blockX(), position.blockY(), position.blockZ())
        if (first) saved.cornerOne = corner else saved.cornerTwo = corner
        save()
        return true
    }

    fun addReward(
        name: String,
        command: String,
    ): Boolean {
        val saved = definitions[name] ?: return false
        if (command.isBlank()) return false
        saved.rewardCommands += command.removePrefix("/")
        save()
        return true
    }

    fun removeReward(
        name: String,
        index: Int,
    ): Boolean {
        val saved = definitions[name] ?: return false
        if (index !in saved.rewardCommands.indices) return false
        saved.rewardCommands.removeAt(index)
        save()
        return true
    }

    fun rewards(name: String): List<String>? = definitions[name]?.rewardCommands?.toList()

    fun addSchedule(
        name: String,
        time: String,
    ): Boolean {
        val saved = definitions[name] ?: return false
        val parsed = runCatching { LocalTime.parse(time) }.getOrNull() ?: return false
        val normalized = parsed.withNano(0).toString()
        if (normalized in saved.schedules) return false
        saved.schedules += normalized
        save()
        return true
    }

    fun removeSchedule(
        name: String,
        time: String,
    ): Boolean {
        val saved = definitions[name] ?: return false
        val parsed = runCatching { LocalTime.parse(time) }.getOrNull() ?: return false
        val removed = saved.schedules.remove(parsed.withNano(0).toString())
        if (removed) save()
        return removed
    }

    fun schedules(name: String): List<String>? = definitions[name]?.schedules?.toList()

    fun start(name: String): Boolean {
        val definition = resolve(name) ?: return false
        if (active.containsKey(name)) return false

        val now = System.currentTimeMillis()
        active[name] =
            ActiveKoth(
                definition = definition,
                startedAt = now,
                endsAt = now + definition.saved.eventSeconds * 1000,
            )
        broadcast(Component.text("KOTH started: ${definition.saved.name}", NamedTextColor.GOLD))
        return true
    }

    fun stop(name: String): Boolean {
        if (name !in active) return false
        finish(name, null)
        return true
    }

    fun status(name: String): String? {
        val saved = definitions[name] ?: return null
        val state = active[name] ?: return "${saved.name}: ${if (resolve(name) == null) "incomplete" else "inactive"}"
        val eventRemaining = remainingSeconds(state.endsAt, System.currentTimeMillis())
        val capturer = findPlayer(state.capturer)?.username ?: "nobody"
        return "${saved.name}: active, ${eventRemaining}s left, capturing: $capturer"
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
                        .firstOrNull { isInside(state.definition, it) }
                if (player != null) beginCapture(state, player.uuid, now)
            }

            val capturer = findPlayer(state.capturer)
            when {
                state.capturer != null && capturer == null -> resetCapture(state)
                capturer != null && !isInside(state.definition, capturer) -> resetCapture(state)
                capturer != null && now - (state.captureStartedAt ?: now) >= state.definition.saved.captureSeconds * 1000 -> {
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

    private fun startScheduled(dateTime: LocalDateTime) {
        for ((name, saved) in definitions) {
            for (time in saved.schedules.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }) {
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
        state.bossBars.forEach { (uuid, bar) -> onlinePlayers.firstOrNull { it.uuid == uuid }?.hideBossBar(bar) }
        state.bossBars.clear()
        state.visibleTo.clear()

        val winnerPlayer = findPlayer(winner)
        if (winnerPlayer != null) {
            giveRewards(state.definition, winnerPlayer)
            broadcast(Component.text("KOTH ${state.definition.saved.name} captured by ${winnerPlayer.username}!", NamedTextColor.GREEN))
        }
    }

    private fun giveRewards(
        definition: Definition,
        player: Player,
    ) {
        for (command in definition.saved.rewardCommands) {
            MinecraftServer
                .getCommandManager()
                .executeServerCommand(
                    command
                        .removePrefix("/")
                        .replace("%player%", player.username)
                        .replace("%koth%", definition.saved.name),
                )
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
                    (state.definition.saved.captureSeconds * 1000 - (now - (state.captureStartedAt ?: now))).coerceAtLeast(0)
                }
            val captureDuration = (state.definition.saved.captureSeconds * 1000).coerceAtLeast(1)

            for (player in players) {
                val nearby =
                    player.instance === state.definition.instance &&
                        player.position.distanceSquared(state.definition.zone.center) <=
                        state.definition.saved.displayRadiusBlocks * state.definition.saved.displayRadiusBlocks
                val bar =
                    state.bossBars.getOrPut(player.uuid) {
                        BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
                    }

                if (!nearby) {
                    if (state.visibleTo.remove(player.uuid)) player.hideBossBar(bar)
                    continue
                }

                if (state.capturer == player.uuid && captureRemaining != null) {
                    bar.name(Component.text("KOTH ${state.definition.saved.name} | Capture: ${formatTime(captureRemaining)}"))
                    bar.progress((captureRemaining.toFloat() / captureDuration).coerceIn(0f, 1f))
                } else {
                    bar.name(Component.text("KOTH ${state.definition.saved.name} | Time left: ${formatTime(eventRemaining)}"))
                    bar.progress((eventRemaining.toFloat() / eventDuration).coerceIn(0f, 1f))
                }

                if (state.visibleTo.add(player.uuid)) player.showBossBar(bar)
            }
        }
    }

    internal fun isInside(
        definition: Definition,
        player: Player,
        position: Pos = player.position,
    ): Boolean =
        player.uuid !in deadPlayers &&
            player.gameMode != GameMode.SPECTATOR &&
            player.instance === definition.instance &&
            definition.zone.contains(position)

    private fun resolve(name: String): Definition? = definitions[name]?.let(::resolve)

    private fun resolve(saved: SavedKoth): Definition? {
        val world = saved.world ?: return null
        val first = saved.cornerOne ?: return null
        val second = saved.cornerTwo ?: return null
        val instance = MinecraftServer.getInstanceManager().instances.firstOrNull { it.getDimensionName() == world } ?: return null
        return Definition(saved, instance, KothZone(BlockVec(first.x, first.y, first.z), BlockVec(second.x, second.y, second.z)))
    }

    private fun load() {
        definitions.clear()
        if (!Files.exists(file)) return
        runCatching {
            Files.newBufferedReader(file).use { reader ->
                val type = object : TypeToken<List<SavedKoth>>() {}.type
                gson.fromJson<List<SavedKoth>?>(reader, type).orEmpty()
            }
        }.onSuccess { saved ->
            saved.forEach { entry ->
                if (valid(entry) && definitions.putIfAbsent(entry.name, entry) == null) return@forEach
                System.err.println("Skipping invalid or duplicate KOTH '${entry.name}' in $file")
            }
        }.onFailure { error ->
            System.err.println("Failed to load KOTHs from $file: ${error.message}")
        }
    }

    private fun save() {
        if (!::file.isInitialized) return
        runCatching {
            Files.createDirectories(file.parent)
            Files.newBufferedWriter(file).use { writer -> gson.toJson(definitions.values.toList(), writer) }
        }.onFailure { error ->
            System.err.println("Failed to save KOTHs to $file: ${error.message}")
        }
    }

    private fun valid(saved: SavedKoth): Boolean =
        saved.name.isNotBlank() &&
            saved.captureSeconds > 0 &&
            saved.eventSeconds > 0 &&
            saved.displayRadiusBlocks > 0 &&
            saved.rewardCommands.all(String::isNotBlank) &&
            saved.schedules.all { runCatching { LocalTime.parse(it) }.isSuccess }

    private fun findPlayer(uuid: UUID?): Player? =
        uuid?.let { target -> MinecraftServer.getConnectionManager().onlinePlayers.firstOrNull { it.uuid == target } }

    private fun remainingSeconds(
        end: Long,
        now: Long,
    ): Long = ((end - now).coerceAtLeast(0) + 999) / 1000

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds.coerceAtLeast(0) + 999) / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun broadcast(message: Component) {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.sendMessage(message) }
    }
}
