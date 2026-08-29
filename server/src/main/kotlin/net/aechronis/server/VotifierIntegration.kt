package net.aechronis.server

import net.aechronis.server.modules.ModuleEvents
import net.aechronis.votifier.VoteReceivedEvent
import net.aechronis.votifier.VotifierModule
import net.aechronis.votifier.VotifierOptions
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerSpawnEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties

object VotifierIntegration {
    private const val VOTE_CRATE_ID = "vote"
    private const val GEM_REWARD = 10L
    private val usernamePattern = Regex("[A-Za-z0-9_]{1,16}")
    private var running: RunningVotifierIntegration? = null

    @Synchronized
    fun initialize() {
        running?.let { state ->
            check(!state.cleanupStarted) { "Votifier integration cleanup is incomplete" }
            check(state.fullyStarted) { "Votifier integration initialization is incomplete" }
            return
        }
        val dataDirectory = Path.of("votifier")
        val state =
            RunningVotifierIntegration(
                eventNode = EventNode.all("vote-rewards"),
                pendingRewards = PendingVoteRewards(dataDirectory.resolve("pending-rewards.properties")),
            )
        running = state
        try {
            state.eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
                drainPendingRewards(state, event.player)
            }
            state.eventNode.addListener(VoteRewardsAvailableEvent::class.java) {
                retryOnlineRewards(state)
            }
            state.eventNodeCleanupPending = true
            ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), state.eventNode)
            state.moduleCleanupPending = true
            VotifierModule.initialize(
                options =
                    VotifierOptions(
                        dataDirectory = dataDirectory,
                    ),
                configureEventNode = { node ->
                    node.addListener(VoteReceivedEvent::class.java) { event ->
                        onVoteReceived(state, event)
                    }
                },
                attachEventNode = { node -> ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), node) },
            )
            retryOnlineRewards(state)
            state.fullyStarted = true
        } catch (error: Throwable) {
            runCatching(::shutdown).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    @Synchronized
    fun shutdown() {
        val state = running ?: return
        state.cleanupStarted = true
        var failure: Throwable? = null

        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }

        if (state.moduleCleanupPending) {
            cleanup {
                VotifierModule.shutdown()
                state.moduleCleanupPending = false
            }
        }
        if (state.eventNodeCleanupPending) {
            cleanup {
                MinecraftServer.getGlobalEventHandler().removeChild(state.eventNode)
                state.eventNodeCleanupPending = false
            }
        }
        if (!state.moduleCleanupPending && !state.eventNodeCleanupPending) running = null
        failure?.let { throw it }
    }

    private fun onVoteReceived(
        state: RunningVotifierIntegration,
        event: VoteReceivedEvent,
    ) {
        val username = event.username.trim()
        if (!usernamePattern.matches(username)) {
            println("Ignoring vote with invalid username '$username'")
            return
        }

        val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(username)
        if (player == null) {
            state.pendingRewards.add(username)
            return
        }

        drainPendingRewards(state, player)
        if (!giveReward(player)) state.pendingRewards.add(username)
    }

    private fun retryOnlineRewards(state: RunningVotifierIntegration) {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { player ->
            drainPendingRewards(state, player)
        }
    }

    private fun drainPendingRewards(
        state: RunningVotifierIntegration,
        player: Player,
    ) {
        val rewards = state.pendingRewards.count(player.username)
        repeat(rewards) {
            if (!giveReward(player)) return
            state.pendingRewards.remove(player.username)
        }
    }

    private fun giveReward(player: Player): Boolean {
        val request = VoteRewardRequest(player, VOTE_CRATE_ID, GEM_REWARD)
        Server.eventNode.call(request)
        return request.granted
    }
}

class VoteRewardRequest(
    val player: Player,
    val itemId: String,
    val gems: Long,
    var granted: Boolean = false,
) : Event

class VoteRewardsAvailableEvent : Event

private class RunningVotifierIntegration(
    val eventNode: EventNode<Event>,
    val pendingRewards: PendingVoteRewards,
) {
    var fullyStarted = false
    var cleanupStarted = false
    var moduleCleanupPending = false
    var eventNodeCleanupPending = false
}

private class PendingVoteRewards(
    private val file: Path,
) {
    private val rewards = linkedMapOf<String, Int>()

    init {
        load()
    }

    fun add(username: String) {
        val key = username.lowercase()
        rewards[key] = Math.addExact(rewards[key] ?: 0, 1)
        save()
    }

    fun count(username: String): Int = rewards[username.lowercase()] ?: 0

    fun remove(username: String) {
        val key = username.lowercase()
        val remaining = (rewards[key] ?: return) - 1
        if (remaining > 0) rewards[key] = remaining else rewards.remove(key)
        save()
    }

    private fun load() {
        if (!Files.exists(file)) return
        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        properties.forEach { (username, count) ->
            val parsedCount = (count as String).toIntOrNull()
            if (parsedCount != null && parsedCount > 0) rewards[username as String] = parsedCount
        }
    }

    private fun save() {
        file.parent?.let(Files::createDirectories)
        val properties = Properties()
        rewards.forEach { (username, count) -> properties.setProperty(username, count.toString()) }
        Files
            .newOutputStream(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { properties.store(it, "Pending Votifier rewards") }
    }
}
