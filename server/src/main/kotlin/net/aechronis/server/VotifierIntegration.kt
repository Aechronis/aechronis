package net.aechronis.server

import net.aechronis.vanilla.managers.Crates
import net.aechronis.votifier.VoteReceivedEvent
import net.aechronis.votifier.VotifierModule
import net.aechronis.votifier.VotifierOptions
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.CommandResult
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerSpawnEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties

object VotifierIntegration {
    private const val VOTE_CRATE_ID = "vote"
    private const val GEM_REWARD = 10
    private val usernamePattern = Regex("[A-Za-z0-9_]{1,16}")
    private val eventNode = EventNode.all("vote-rewards")
    private lateinit var pendingRewards: PendingVoteRewards

    fun initialize() {
        val dataDirectory = Path.of("votifier")
        pendingRewards = PendingVoteRewards(dataDirectory.resolve("pending-rewards.properties"))
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        eventNode.addListener(PlayerSpawnEvent::class.java, ::onPlayerSpawn)

        VotifierModule.initialize(
            VotifierOptions(
                dataDirectory = dataDirectory,
            ),
        )
        VotifierModule.eventNode.addListener(VoteReceivedEvent::class.java, ::onVoteReceived)
    }

    fun shutdown() {
        MinecraftServer.getGlobalEventHandler().removeChild(eventNode)
        VotifierModule.shutdown()
    }

    private fun onVoteReceived(event: VoteReceivedEvent) {
        val username = event.username.trim()
        if (!usernamePattern.matches(username)) {
            println("Ignoring vote with invalid username '$username'")
            return
        }

        val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(username)
        if (player == null || !giveReward(player)) {
            pendingRewards.add(username)
        }
    }

    private fun onPlayerSpawn(event: PlayerSpawnEvent) {
        val player = event.player
        val rewards = pendingRewards.count(player.username)
        repeat(rewards) {
            if (!giveReward(player)) return
            pendingRewards.remove(player.username)
        }
    }

    private fun giveReward(player: Player): Boolean {
        val result =
            MinecraftServer
                .getCommandManager()
                .executeServerCommand("gem give ${player.username} $GEM_REWARD")
        if (result.type != CommandResult.Type.SUCCESS) return false

        val crate = Crates.itemFor(VOTE_CRATE_ID)
        if (!player.inventory.addItemStack(crate)) player.dropItem(crate)
        return true
    }
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
