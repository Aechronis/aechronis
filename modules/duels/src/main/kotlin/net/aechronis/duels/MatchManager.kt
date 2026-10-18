package net.aechronis.duels

import net.aechronis.combat.objects.Vehicle
import net.aechronis.server.Server
import net.aechronis.server.io.AtomicFiles
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.serdes.PlayerDataDeserializer
import net.aechronis.vanilla.serdes.PlayerDataSerializer
import net.aechronis.vanilla.serdes.PlayerDataSnapshot
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/** Pre-match player state on disk, so a crash, disconnect or reload mid-match cannot lose an inventory. */
object SnapshotStore {
    private val dir: Path = Path.of("duels", "snapshots")

    private fun file(uuid: UUID) = dir.resolve("$uuid.dat")

    fun exists(uuid: UUID) = Files.exists(file(uuid))

    fun save(player: Player) {
        val tag = PlayerDataSerializer.serialize(PlayerDataSnapshot.capture(player))
        Files.createDirectories(dir)
        val target = file(player.uuid)
        AtomicFiles.withTemporaryFile(target) { temporary ->
            Files.newOutputStream(temporary).use { BinaryTagIO.writer().write(tag, it, BinaryTagIO.Compression.GZIP) }
            AtomicFiles.replace(temporary, target, false)
        }
    }

    fun load(uuid: UUID): CompoundBinaryTag? =
        runCatching { Files.newInputStream(file(uuid)).use { BinaryTagIO.reader().read(it, BinaryTagIO.Compression.GZIP) } }.getOrNull()

    fun delete(uuid: UUID) {
        Files.deleteIfExists(file(uuid))
    }
}

object MatchManager {
    private val byPlayer = ConcurrentHashMap<UUID, Match>()
    private val matches = ConcurrentHashMap.newKeySet<Match>()
    private val teamColors = listOf(NamedTextColor.RED, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.YELLOW)

    /** Each match is its own loaded instance, so cap how many run at once. */
    const val MAX_MATCHES = 10

    fun isFull(): Boolean = matches.size >= MAX_MATCHES

    fun matchOf(player: Player): Match? = byPlayer[player.uuid]

    fun inMatch(player: Player) = byPlayer.containsKey(player.uuid)

    /** [teams] is a list of rosters; each roster becomes a [Team] and spawns at the map spawn with its index. */
    fun start(
        mode: MatchMode,
        kit: Kit,
        map: MapDef,
        teams: List<List<Player>>,
    ): Match {
        val instance = Maps.createInstance(map)
        val built =
            teams.mapIndexed { i, roster ->
                Team(i, "Team ${i + 1}", teamColors[i % teamColors.size], roster.map { it.uuid }.toSet())
            }
        val match = Match(mode, kit, map, instance, built)
        matches += match
        val joined =
            teams.flatMapIndexed { i, roster ->
                roster.map { player ->
                    Vehicle.exit(player)
                    SnapshotStore.save(player)
                    byPlayer[player.uuid] = match
                    player.setInstance(instance, map.spawnFor(i)).thenRun {
                        ModuleScheduler.scheduleNextTick { prepare(match, player) }
                    }
                }
            }
        CompletableFuture.allOf(*joined.toTypedArray()).whenComplete { _, _ ->
            ModuleScheduler.scheduleNextTick { countdown(match) }
        }
        mode.onStart(match)
        return match
    }

    private fun prepare(
        match: Match,
        player: Player,
    ) {
        if (!player.isOnline || byPlayer[player.uuid] !== match) return
        player.gameMode = GameMode.SURVIVAL
        player.isAllowFlying = false
        player.isFlying = false
        player.health = 20f
        player.food = 20
        player.foodSaturation = 5f
        player.setFireTicks(0)
        player.clearEffects()
        match.kit.apply(player)
    }

    private fun countdown(match: Match) {
        var n = 3
        ModuleScheduler.submitTask(
            Supplier {
                if (match.state != MatchState.COUNTDOWN) return@Supplier TaskSchedule.stop()
                if (n > 0) {
                    title(match, Component.text(n, NamedTextColor.YELLOW), Component.text(match.kit.displayName))
                    n--
                    TaskSchedule.seconds(1)
                } else {
                    match.state = MatchState.ACTIVE
                    title(match, Component.text("Fight!", NamedTextColor.RED), Component.empty())
                    TaskSchedule.stop()
                }
            },
        )
    }

    private fun title(
        match: Match,
        main: Component,
        sub: Component,
    ) = match.players().forEach { it.showTitle(Title.title(main, sub)) }

    /** Called when a player would die, leaves, or falls out of the map. */
    fun eliminate(
        match: Match,
        uuid: UUID,
        left: Boolean = false,
    ) {
        if (match.state == MatchState.ENDING) return
        val team = match.teamOf(uuid) ?: return
        if (!team.alive.remove(uuid)) return
        val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)
        val name = player?.username ?: "A player"
        match.players().forEach {
            it.sendMessage(Component.text(if (left) "$name left the match." else "$name was defeated.", NamedTextColor.GOLD))
        }
        if (player != null && !left) {
            player.health = 20f
            player.gameMode = GameMode.SPECTATOR
        }
        if (match.mode.isOver(match)) end(match, match.teams.singleOrNull { it.alive.isNotEmpty() })
    }

    fun end(
        match: Match,
        winner: Team?,
    ) {
        if (match.state == MatchState.ENDING) return
        match.state = MatchState.ENDING
        match.mode.onEnd(match, winner)
        val text =
            if (winner == null) {
                Component.text("The match ended in a draw.", NamedTextColor.GOLD)
            } else {
                val names = winner.members.mapNotNull { MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(it)?.username }
                Component.text("${names.joinToString(", ")} won the ${match.mode.displayName}!", NamedTextColor.GREEN)
            }
        match.players().forEach { p ->
            p.sendMessage(text)
            val won = winner != null && p.uuid in winner.members
            p.showTitle(
                when {
                    winner == null -> Title.title(Component.text("DRAW", NamedTextColor.GOLD), Component.empty())
                    won ->
                        Title.title(
                            Component.text("VICTORY", NamedTextColor.GREEN),
                            Component.text("You won the ${match.mode.displayName}"),
                        )
                    else ->
                        Title.title(
                            Component.text("DEFEAT", NamedTextColor.RED),
                            Component.text("You lost the ${match.mode.displayName}"),
                        )
                },
            )
        }
        ModuleScheduler.buildTask { finish(match) }.delay(TaskSchedule.seconds(3)).schedule()
    }

    private fun finish(match: Match) {
        match.members.forEach { byPlayer.remove(it, match) }
        val returned = match.players().filter { it.instance === match.instance }.map { returnToSpawn(it) }
        CompletableFuture.allOf(*returned.toTypedArray()).whenComplete { _, _ ->
            ModuleScheduler.scheduleNextTick {
                MinecraftServer.getInstanceManager().unregisterInstance(match.instance)
                matches -= match
            }
        }
    }

    /** Drops a disconnecting player from the roster; their snapshot stays on disk until they rejoin. */
    fun onDisconnect(player: Player) {
        val match = byPlayer.remove(player.uuid) ?: return
        eliminate(match, player.uuid, left = true)
    }

    /** Moves the player to the world spawn and gives back the inventory and state saved before the match. */
    fun returnToSpawn(player: Player): CompletableFuture<Void> {
        val spawn = Server.spawnPoint
        val saved = SnapshotStore.load(player.uuid)
        val moved =
            if (player.instance === Server.instance) {
                CompletableFuture.completedFuture<Void>(null)
            } else {
                player.setInstance(Server.instance, spawn)
            }
        return moved.thenRun {
            ModuleScheduler.scheduleNextTick {
                if (!player.isOnline) return@scheduleNextTick
                player.inventory.cursorItem = net.minestom.server.item.ItemStack.AIR
                if (saved != null) {
                    PlayerDataDeserializer.deserialize(player, saved)
                    SnapshotStore.delete(player.uuid)
                } else {
                    // Unreadable or missing snapshot: strip the kit, keep any file on disk for manual recovery.
                    player.inventory.clear()
                    player.gameMode = GameMode.SURVIVAL
                    System.err.println("[Duels] No readable snapshot for ${player.username}; cleared kit")
                }
                player.teleport(spawn)
            }
        }
    }

    /** Shutdown/reload: end everything now and hand every player back their state. */
    fun abortAll() {
        matches.toList().forEach { match ->
            match.state = MatchState.ENDING
            match.members.forEach { byPlayer.remove(it, match) }
            match.players().filter { it.instance === match.instance }.forEach { returnToSpawn(it) }
        }
        matches.clear()
    }
}
