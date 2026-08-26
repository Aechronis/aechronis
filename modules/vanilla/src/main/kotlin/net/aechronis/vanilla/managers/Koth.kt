package net.aechronis.vanilla.managers

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
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
        var nextAnnouncementAt: Long = startedAt + ANNOUNCEMENT_INTERVAL_MS,
        var capturer: UUID? = null,
        var captureStartedAt: Long? = null,
        val bossBars: MutableMap<UUID, BossBar> = mutableMapOf(),
        val visibleTo: MutableSet<UUID> = mutableSetOf(),
    )

    private val definitions = linkedMapOf<String, SavedKoth>()
    private val scheduledRuns = mutableMapOf<String, LocalDateTime>()
    internal val active = linkedMapOf<String, ActiveKoth>()
    internal val deadPlayers = mutableSetOf<UUID>()
    private val captureGlowReferences = mutableMapOf<UUID, Int>()
    private val captureGlowPreviousStates = mutableMapOf<UUID, Boolean>()
    private val captureGlowPlayers = mutableMapOf<UUID, Player>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val cronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    private lateinit var file: Path

    private const val ANNOUNCEMENT_INTERVAL_MS = 10 * 60 * 1000L

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
        val normalized = command.trim().removePrefix("/").trim()
        if (normalized.isBlank()) return false
        saved.rewardCommands += normalized
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
        expression: String,
    ): Boolean {
        val saved = definitions[name] ?: return false
        val normalized = normalizeSchedule(expression) ?: return false
        if (normalized in saved.schedules) return false
        saved.schedules += normalized
        save()
        return true
    }

    fun removeSchedule(
        name: String,
        expression: String,
    ): Boolean {
        val saved = definitions[name] ?: return false
        val normalized = normalizeSchedule(expression) ?: return false
        val removed = saved.schedules.remove(normalized)
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

            announceIfDue(state, now)

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
                    finish(name, capturer)
                }
            }
        }

        updateBossBars(now)
    }

    private fun scheduledTick() {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        tickAt(System.currentTimeMillis(), now.toLocalDateTime().withNano(0))
    }

    private fun announceIfDue(
        state: ActiveKoth,
        now: Long,
    ) {
        if (now < state.nextAnnouncementAt) return
        state.nextAnnouncementAt = now + ANNOUNCEMENT_INTERVAL_MS
        val remaining = formatTime((state.endsAt - now).coerceAtLeast(0))
        broadcast(Component.text("KOTH ${state.definition.saved.name} is still active! $remaining remaining.", NamedTextColor.GOLD))
    }

    private fun startScheduled(dateTime: LocalDateTime) {
        val minute = dateTime.withSecond(0).withNano(0)
        for ((name, saved) in definitions) {
            if (scheduledRuns[name] == minute || saved.schedules.none { matchesSchedule(it, minute) }) continue
            scheduledRuns[name] = minute
            start(name)
        }
    }

    internal fun beginCapture(
        state: ActiveKoth,
        player: UUID,
        now: Long,
    ) {
        beginCapture(state, player, now, findPlayer(player))
    }

    internal fun beginCapture(
        state: ActiveKoth,
        player: Player,
        now: Long,
    ) {
        beginCapture(state, player.uuid, now, player)
    }

    private fun beginCapture(
        state: ActiveKoth,
        player: UUID,
        now: Long,
        playerEntity: Player?,
    ) {
        state.capturer = player
        state.captureStartedAt = now
        addCaptureGlow(player, playerEntity)
    }

    internal fun resetCaptures(player: UUID) {
        active.values.filter { it.capturer == player }.forEach(::resetCapture)
    }

    internal fun resetCapture(state: ActiveKoth) {
        val capturer = state.capturer
        if (capturer != null) removeCaptureGlow(capturer)
        state.capturer = null
        state.captureStartedAt = null
    }

    private fun addCaptureGlow(
        player: UUID,
        playerEntity: Player?,
    ) {
        val references = captureGlowReferences[player] ?: 0
        if (references == 0) {
            val target = playerEntity ?: findPlayer(player) ?: return
            captureGlowPreviousStates[player] = target.isGlowing
            captureGlowPlayers[player] = target
            target.isGlowing = true
        }
        captureGlowReferences[player] = references + 1
    }

    private fun removeCaptureGlow(player: UUID) {
        val references = captureGlowReferences[player] ?: return
        if (references > 1) {
            captureGlowReferences[player] = references - 1
            return
        }

        captureGlowPlayers.remove(player)?.isGlowing = captureGlowPreviousStates.remove(player) ?: false
        captureGlowReferences.remove(player)
    }

    private fun finish(
        name: String,
        winner: Player?,
    ) {
        val state = active.remove(name) ?: return
        resetCapture(state)
        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers.toList()
        state.bossBars.forEach { (uuid, bar) -> onlinePlayers.firstOrNull { it.uuid == uuid }?.hideBossBar(bar) }
        state.bossBars.clear()
        state.visibleTo.clear()

        if (winner != null) {
            giveRewards(state.definition, winner)
            broadcast(Component.text("KOTH ${state.definition.saved.name} captured by ${winner.username}!", NamedTextColor.GREEN))
        }
    }

    private fun giveRewards(
        definition: Definition,
        player: Player,
    ) {
        for (savedCommand in definition.saved.rewardCommands) {
            val command =
                savedCommand
                    .trim()
                    .removePrefix("/")
                    .trim()
                    .replace("%player%", player.username)
                    .replace("%koth%", definition.saved.name)
            if (command.isBlank()) continue

            val result = runCatching { MinecraftServer.getCommandManager().executeServerCommand(command) }
            val exception = result.exceptionOrNull()
            val commandResult = result.getOrNull()
            if (exception != null || commandResult?.type != net.minestom.server.command.builder.CommandResult.Type.SUCCESS) {
                val details = exception?.message ?: commandResult?.type ?: "unknown result"
                System.err.println("KOTH '${definition.saved.name}' reward failed for ${player.username}: '$command' ($details)")
                player.sendMessage(Component.text("KOTH reward failed: $command", NamedTextColor.RED))
            }
        }
    }

    /** Updates all visible KOTH bars once per scheduler tick or after a capture state change. */
    internal fun updateBossBars(now: Long) {
        val players = MinecraftServer.getConnectionManager().onlinePlayers.toList()
        for (state in active.values) {
            players.forEach { player -> updateBossBar(state, player, player.position, now, updateContent = true) }
        }
    }

    /**
     * Reconciles visibility for one moving player without sending progress packets for every
     * movement packet. Content is refreshed by [updateBossBars] once per second.
     */
    internal fun updateBossBarsFor(
        player: Player,
        position: Pos = player.position,
        now: Long,
    ) {
        for (state in active.values) {
            updateBossBar(state, player, position, now, updateContent = false)
        }
    }

    private fun updateBossBar(
        state: ActiveKoth,
        player: Player,
        position: Pos,
        now: Long,
        updateContent: Boolean,
    ) {
        val nearby =
            player.instance === state.definition.instance &&
                position.distanceSquared(state.definition.zone.center) <=
                state.definition.saved.displayRadiusBlocks * state.definition.saved.displayRadiusBlocks
        val existingBar = state.bossBars[player.uuid]
        if (!nearby) {
            if (state.visibleTo.remove(player.uuid) && existingBar != null) player.hideBossBar(existingBar)
            return
        }

        val bar =
            existingBar ?: BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS).also {
                state.bossBars[player.uuid] = it
            }
        if (updateContent || player.uuid !in state.visibleTo) {
            val eventRemaining = (state.endsAt - now).coerceAtLeast(0)
            val eventDuration = (state.endsAt - state.startedAt).coerceAtLeast(1)
            val capturer = findPlayer(state.capturer)
            val captureRemaining =
                capturer?.let {
                    (state.definition.saved.captureSeconds * 1000 - (now - (state.captureStartedAt ?: now))).coerceAtLeast(0)
                }
            val captureDuration = (state.definition.saved.captureSeconds * 1000).coerceAtLeast(1)

            if (capturer != null && captureRemaining != null) {
                val captureMessage =
                    "KOTH ${state.definition.saved.name} | Capturing: ${capturer.username} | Time left: " +
                        formatTime(captureRemaining)
                bar.name(Component.text(captureMessage))
                bar.progress((captureRemaining.toFloat() / captureDuration).coerceIn(0f, 1f))
            } else {
                bar.name(Component.text("KOTH ${state.definition.saved.name} | Time left: ${formatTime(eventRemaining)}"))
                bar.progress((eventRemaining.toFloat() / eventDuration).coerceIn(0f, 1f))
            }
        }

        if (state.visibleTo.add(player.uuid)) player.showBossBar(bar)
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
            var migrated = false
            saved.forEach { entry ->
                val normalizedSchedules = entry.schedules.map(::normalizeSchedule)
                if (normalizedSchedules.any { it == null }) {
                    System.err.println("Skipping invalid or duplicate KOTH '${entry.name}' in $file")
                    return@forEach
                }
                val schedules = normalizedSchedules.filterNotNull()
                if (entry.schedules != schedules) {
                    entry.schedules.clear()
                    entry.schedules += schedules
                    migrated = true
                }
                if (valid(entry) && definitions.putIfAbsent(entry.name, entry) == null) return@forEach
                System.err.println("Skipping invalid or duplicate KOTH '${entry.name}' in $file")
            }
            if (migrated) save()
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
            saved.schedules.all { normalizeSchedule(it) != null }

    /**
     * Accepts five-field Unix cron expressions and migrates the legacy HH:mm schedule format.
     */
    internal fun normalizeSchedule(expression: String): String? {
        val trimmed = expression.trim()
        if (trimmed.isBlank()) return null
        runCatching { LocalTime.parse(trimmed) }.getOrNull()?.let { time ->
            return "${time.minute} ${time.hour} * * *"
        }
        return runCatching {
            cronParser.parse(trimmed).also { it.validate() }.asString()
        }.getOrNull()
    }

    internal fun matchesSchedule(
        expression: String,
        minute: LocalDateTime,
    ): Boolean =
        runCatching {
            val cron = cronParser.parse(expression).also { it.validate() }
            ExecutionTime.forCron(cron).isMatch(minute.atZone(ZoneId.systemDefault()))
        }.getOrDefault(false)

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
