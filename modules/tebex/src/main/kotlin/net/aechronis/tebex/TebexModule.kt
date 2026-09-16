package net.aechronis.tebex

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.MinecraftServer
import net.minestom.server.command.ConsoleSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.CommandResult
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TebexModule : AechronisModule {
    override val id = "tebex"
    private val gate = Any()
    private var running = false
    private var worker: java.util.concurrent.ScheduledExecutorService? = null
    private var tickTask: Task? = null
    private var api: TebexApi? = null
    private lateinit var journal: DeliveryJournal
    private val pending = linkedMapOf<Long, Delivery>()

    @Volatile
    private var onlineIds = emptySet<String>()

    override fun initialize(context: ModuleContext) {
        val secret = System.getenv("TEBEX_SECRET")?.trim().orEmpty()
        if (secret.isEmpty()) {
            println("[Tebex] Disabled: set TEBEX_SECRET to the game server secret key to enable purchase delivery")
            return
        }
        journal = DeliveryJournal(Path.of("tebex", "deliveries.log"))
        val client = TebexApi(secret)
        api = client
        running = true
        registerRecoveryCommand()
        tickTask =
            ModuleScheduler
                .buildTask { tick() }
                .delay(TaskSchedule.nextTick())
                .repeat(TaskSchedule.tick(1))
                .schedule()
        val executor =
            Executors.newSingleThreadScheduledExecutor { action ->
                Thread(action, "tebex-queue").also { it.isDaemon = true }
            }
        worker = executor
        executor.scheduleWithFixedDelay({ poll(client) }, 1, 1, TimeUnit.SECONDS)
        println("[Tebex] Purchase delivery enabled; ${journal.uncertain().size} command(s) need reconciliation")
    }

    private fun poll(client: TebexApi) {
        if (!synchronized(gate) { running } || System.currentTimeMillis() < journal.nextPoll) return
        try {
            // Persist the fallback first, so failures and reloads cannot create a tight request loop.
            journal.record("N", System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5))
            journal.completed().chunked(100).forEach { ids ->
                client.acknowledge(ids)
                ids.forEach { journal.record("A", it) }
            }
            val queue = client.queue()
            val seconds = queue.meta.next_check.coerceAtLeast(1)
            require(seconds <= TimeUnit.DAYS.toSeconds(365)) { "Invalid Tebex polling interval" }
            journal.record("N", System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds))
            if (queue.meta.execute_offline) enqueue(client.commands(), null)
            queue.players.filter { normalizeUuid(it.uuid) in onlineIds }.forEach { player ->
                enqueue(client.commands(player.id), player)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!synchronized(gate) { running }) return
            try {
                val seconds = (error as? TebexApiException)?.retrySeconds?.coerceAtMost(TimeUnit.DAYS.toSeconds(365)) ?: 300
                journal.record("N", maxOf(journal.nextPoll, System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds)))
            } catch (_: Exception) {
                synchronized(gate) { running = false }
                System.err.println("[Tebex] Journal write failed; deliveries stopped until reload")
            }
            val status = (error as? TebexApiException)?.status
            System.err.println("[Tebex] Queue check failed (${status?.let { "HTTP $it" } ?: error.javaClass.simpleName}); retry deferred")
        }
    }

    private fun enqueue(
        commands: List<TebexCommand>,
        onlinePlayer: TebexPlayer?,
    ) {
        synchronized(gate) {
            if (!running) return
            commands.forEach { command ->
                if (command.id <= 0 || journal.state(command.id) != null || pending.containsKey(command.id)) return@forEach
                val player = onlinePlayer ?: command.player ?: return@forEach
                // Minecraft identities must remain single command arguments.
                if (!player.name.matches(Regex("[A-Za-z0-9_]{1,16}")) || !normalizeUuid(player.uuid).matches(Regex("[0-9a-f]{32}"))) {
                    System.err.println("[Tebex] Invalid Minecraft identity for command ${command.id}; left queued")
                    return@forEach
                }
                val delay = if (onlinePlayer == null) 0 else command.conditions.delay
                if (delay !in 0..TimeUnit.DAYS.toSeconds(365) ||
                    (onlinePlayer != null && command.conditions.slots !in 0..36)
                ) {
                    return@forEach
                }
                pending[command.id] = Delivery(command, player, onlinePlayer != null, System.nanoTime() + TimeUnit.SECONDS.toNanos(delay))
            }
        }
    }

    private fun tick() {
        synchronized(gate) {
            if (!running) return
            val players =
                MinecraftServer
                    .getConnectionManager()
                    .onlinePlayers
                    .filter { it.isOnline && it.instance != null }
                    .associateBy { normalizeUuid(it.uuid.toString()) }
            onlineIds = players.keys.toSet()
            // Bound the amount of console work in one tick.
            val due =
                pending.values
                    .filter { delivery ->
                        val player = players[normalizeUuid(delivery.player.uuid)]
                        System.nanoTime() >= delivery.due &&
                            (!delivery.online || (player != null && emptySlots(player) >= delivery.command.conditions.slots))
                    }.take(5)
            for (delivery in due) {
                val command = delivery.command
                if (journal.state(command.id) != null) {
                    pending.remove(command.id)
                    continue
                }
                val player = players[normalizeUuid(delivery.player.uuid)]
                if (delivery.online && (player == null || emptySlots(player) < command.conditions.slots)) continue
                val text =
                    command.command
                        .trim()
                        .removePrefix("/")
                        .replace("{name}", player?.username ?: delivery.player.name)
                        .replace("{id}", delivery.player.uuid)
                        .replace("{uuid}", delivery.player.uuid)
                if (text.isBlank() || '\n' in text || '\r' in text) {
                    pending.remove(command.id)
                    continue
                }
                try {
                    journal.record("P", command.id)
                    pending.remove(command.id)
                    val result = MinecraftServer.getCommandManager().executeServerCommand(text)
                    if (result.type == CommandResult.Type.SUCCESS) {
                        journal.record("D", command.id)
                    } else {
                        System.err.println(
                            "[Tebex] Command ${command.id} returned ${result.type}; use console tebex resolve after reviewing delivery",
                        )
                    }
                } catch (error: Exception) {
                    // A command may have mutated state before throwing. Never automatically replay it.
                    running = false
                    System.err.println("[Tebex] Delivery ${command.id} stopped (${error.javaClass.simpleName}); review journal and reload")
                    break
                }
            }
        }
    }

    private fun registerRecoveryCommand() {
        val command = Command("tebex")
        command.setCondition { sender, _ -> sender is ConsoleSender }
        command.setDefaultExecutor { sender, _ ->
            sender.sendMessage("Tebex uncertain IDs: ${journal.uncertain().joinToString()}. Resolve: tebex resolve <id> <retry|complete>")
        }
        val resolve = ArgumentType.Literal("resolve")
        val commandId = ArgumentType.Long("id")
        val action = ArgumentType.Word("action").from("retry", "complete")
        command.addSyntax({ sender, args ->
            synchronized(gate) {
                val target = args.get(commandId)
                if (journal.state(target) != "P") {
                    sender.sendMessage("Only uncertain commands can be resolved.")
                } else {
                    journal.record(if (args.get(action) == "retry") "R" else "D", target)
                    sender.sendMessage("Resolved Tebex command $target; the next queue check will process it.")
                }
            }
        }, resolve, commandId, action)
        ModuleCommands.register(command)
    }

    override fun prepareForShutdown(context: ModuleContext) = stop()

    override fun shutdown(context: ModuleContext) = stop()

    private fun stop() {
        synchronized(gate) {
            running = false
            pending.clear()
            onlineIds = emptySet()
        }
        tickTask?.cancel()
        tickTask = null
        worker?.shutdownNow()
        api?.close()
        check(worker?.awaitTermination(30, TimeUnit.SECONDS) != false) { "Tebex queue worker did not stop" }
        worker = null
        api = null
    }

    private fun emptySlots(player: Player): Int = (0 until 36).count { player.inventory.getItemStack(it).isAir }
}

private fun normalizeUuid(uuid: String): String = uuid.replace("-", "").lowercase()

private data class Delivery(
    val command: TebexCommand,
    val player: TebexPlayer,
    val online: Boolean,
    val due: Long,
)
