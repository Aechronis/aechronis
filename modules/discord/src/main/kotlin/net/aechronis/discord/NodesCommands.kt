package net.aechronis.discord

import kotlinx.coroutines.future.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Resident
import net.aechronis.punishments.Punishments
import net.aechronis.server.CommandOutput
import net.aechronis.server.Permissions
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.utils.Command
import net.luckperms.api.LuckPermsProvider
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.command.ExecutableCommand
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.command.builder.arguments.ArgumentEnum
import net.minestom.server.command.builder.arguments.ArgumentLiteral
import net.minestom.server.command.builder.arguments.ArgumentStringArray
import net.minestom.server.command.builder.arguments.ArgumentWord
import net.minestom.server.command.builder.exception.ArgumentSyntaxException
import net.minestom.server.entity.Player
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.minestom.server.command.builder.Command as MinestomCommand

internal class NodesCommands(
    private val links: AccountLinks,
) {
    // Take the catalogue during module initialization; executions still resolve the live tree.
    val names = roots().map { discordName(it) }
    private val permissionLocks = Array(32) { Mutex() }

    suspend fun execute(
        discordId: String,
        root: String,
        arguments: String,
    ): CommandReply {
        val authorization = links.authorization(discordId) ?: return CommandReply(LINK_FIRST)
        val uuid = authorization.uuid
        if (!validInput(root, arguments)) return CommandReply("Invalid command arguments.")
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val tasks = mutableListOf<CompletableFuture<*>>()
        return withPermissions(uuid) {
            val reply =
                onGameThread {
                    links.withLink(discordId, authorization) { executeLinked(uuid, root, arguments.trim(), lines, tasks) }
                        ?: CommandReply(LINK_FIRST)
                }
            if (tasks.isEmpty()) {
                reply
            } else {
                // Punishment commands reply from async work after the command itself returns.
                tasks.forEach { runCatching { it.await() } }
                CommandReply(lines.toList().ifEmpty { listOf("Command completed.") })
            }
        }
    }

    suspend fun suggest(
        discordId: String,
        root: String,
        arguments: String,
    ): List<String> {
        val authorization = links.authorization(discordId) ?: return emptyList()
        val uuid = authorization.uuid
        if (!validInput(root, arguments)) return emptyList()
        return withPermissions(uuid) {
            onGameThread {
                links
                    .withLink(discordId, authorization) {
                        val resident = Resident.fromUuid(uuid) ?: return@withLink emptyList()
                        val player = online(uuid) ?: OfflinePlayer(uuid, resident.name) { }
                        // Discord trims the trailing space, so a finished word also gets the next argument's values.
                        val finished = arguments.isNotBlank() && !arguments.endsWith(' ')
                        val next = if (finished) candidates(player, root, "$arguments ") else emptyList()
                        (candidates(player, root, arguments) + next)
                            .distinct()
                            .filter { value ->
                                value.length in 1..100 && route(root, value)?.let { permitted(uuid, it) } == true
                            }.take(25)
                    }.orEmpty()
            }
        }
    }

    internal fun executeLinked(
        uuid: UUID,
        root: String,
        arguments: String,
        lines: MutableList<String> = mutableListOf(),
        tasks: MutableList<CompletableFuture<*>> = mutableListOf(),
    ): CommandReply {
        val resident = Resident.fromUuid(uuid) ?: return CommandReply("Join the Minecraft server once to create your Nodes player record.")
        val route = route(root, arguments) ?: return CommandReply("This Nodes command is unavailable.")
        if (!permitted(uuid, route)) return CommandReply("You don't have permission to use this command")
        val punishment = route.commands.first().name in Punishments.commandNames
        val online = online(uuid)
        if (online == null && !punishment && !OfflineCommands.allows(route.path, route.arguments)) {
            return CommandReply("This command requires your linked player to be online in Minecraft (world, inventory, or session action).")
        }
        val receive: (net.kyori.adventure.text.Component) -> Unit = { lines += CommandReply.plain(it) }
        // Punishment replies arrive after this returns, so they must go to Discord rather than the online player.
        val player = online?.takeUnless { punishment } ?: OfflinePlayer(uuid, resident.name, receive)
        val input = (route.commands.first().name + " " + arguments).trimEnd()
        Punishments.commandTasks.set(tasks)
        val result =
            try {
                CommandOutput.capture(uuid, receive) {
                    // Parsing the registered Nodes root preserves conditions, argument validation and all
                    // real executors, without allowing an event listener to rewrite it to a console command.
                    MinecraftServer
                        .getCommandManager()
                        .parseCommand(player, input)
                        .executable()
                        .execute(player)
                }
            } finally {
                Punishments.commandTasks.remove()
            }
        if (result.type() == ExecutableCommand.Result.Type.EXECUTOR_EXCEPTION) {
            lines += "The command failed; it may have partially run. Check its state before retrying."
        } else if (lines.isEmpty() && tasks.isEmpty()) {
            lines +=
                when (result.type()) {
                    ExecutableCommand.Result.Type.SUCCESS -> "Command completed. Any menus or confirmations are shown in Minecraft."
                    else -> "The command could not run. Check the arguments or use this command's help."
                }
        }
        return CommandReply(lines.toList())
    }

    internal data class Route(
        val path: String,
        val commands: List<MinestomCommand>,
        val arguments: List<String>,
    )

    internal fun route(
        root: String,
        arguments: String,
    ): Route? {
        var current = roots().firstOrNull { discordName(it) == root } ?: return null
        val chain = mutableListOf(current)
        val path = mutableListOf(root)
        val words = arguments.trim().split(Regex(" +")).filter(String::isNotEmpty)
        var index = 0
        while (index < words.size) {
            val child = current.subcommands.firstOrNull { MinestomCommand.isValidName(it, words[index]) } ?: break
            current = child
            chain += child
            path += child.name
            index++
        }
        return Route(path.joinToString("."), chain, words.drop(index))
    }

    /** Full argument strings that complete the last word of [arguments]. */
    private fun candidates(
        player: Player,
        root: String,
        arguments: String,
    ): List<String> {
        val route = route(root, arguments) ?: return emptyList()
        val input = "${route.commands.first().name} $arguments"
        val suggestion = runCatching { MinecraftServer.getCommandManager().parseCommand(player, input).suggestion(player) }.getOrNull()
        val candidates = mutableListOf<String>()
        if (suggestion != null) {
            suggestion.entries.forEach { entry ->
                val completed = input.take(suggestion.start) + entry.entry
                candidates += completed.substringAfter(' ', "")
            }
        }
        // Include command names even when the parser has no argument suggestion callback.
        val prefix = arguments.substringBeforeLast(' ', "")
        val partial = arguments.substringAfterLast(' ')
        val parent = route(root, if (' ' in arguments) "$prefix " else "")
        parent?.commands?.last()?.subcommands?.forEach { child ->
            if (child.name.startsWith(partial, ignoreCase = true)) {
                candidates += if (prefix.isEmpty()) child.name else "$prefix ${child.name}"
            }
        }
        // Discord has no client-side command tree, so also offer the fixed values of the next argument.
        val typed = if (partial.isEmpty()) route.arguments else route.arguments.dropLast(1)
        route.commands.last().syntaxes.forEach { syntax ->
            nextValues(player, syntax.arguments, typed).forEach { value ->
                if (value.startsWith(partial, ignoreCase = true)) candidates += arguments.dropLast(partial.length) + value
            }
        }
        return candidates.map { it.trimEnd() }
    }

    /** Literal, enum or restricted word values for the argument after [typed], if every typed word parses. */
    private fun nextValues(
        sender: CommandSender,
        syntax: Array<Argument<*>>,
        typed: List<String>,
    ): List<String> {
        if (typed.size >= syntax.size) return emptyList()
        typed.forEachIndexed { index, word ->
            val argument = syntax[index]
            if (argument is ArgumentStringArray) return emptyList()
            try {
                argument.parse(sender, word)
            } catch (_: ArgumentSyntaxException) {
                return emptyList()
            }
        }
        return when (val next = syntax[typed.size]) {
            is ArgumentLiteral -> listOf(next.id)
            is ArgumentEnum<*> -> next.entries()
            is ArgumentWord -> next.restrictions?.toList().orEmpty()
            else -> emptyList()
        }
    }

    private fun permitted(
        uuid: UUID,
        route: Route,
    ): Boolean = route.commands.all { Permissions.hasPermission(uuid, (it as? Command)?.permission) }

    private fun validInput(
        root: String,
        arguments: String,
    ): Boolean = root in names && arguments.length <= 1800 && arguments.none { it.isISOControl() }

    private fun online(uuid: UUID): Player? =
        MinecraftServer
            .getConnectionManager()
            .getOnlinePlayerByUuid(uuid)
            ?.takeIf { it.isOnline && it.instance != null }

    /** Whether [uuid] has [permission] in LuckPerms, online or not. */
    suspend fun allowed(
        uuid: UUID,
        permission: String,
    ): Boolean = withPermissions(uuid) { Permissions.hasPermission(uuid, permission) }

    private suspend fun <T> withPermissions(
        uuid: UUID,
        action: suspend () -> T,
    ): T =
        permissionLocks[(uuid.hashCode() and Int.MAX_VALUE) % permissionLocks.size].withLock {
            val manager = LuckPermsProvider.get().userManager
            val user = manager.loadUser(uuid).await()
            try {
                action()
            } finally {
                manager.cleanupUser(user)
            }
        }

    private companion object {
        const val LINK_FIRST = "Link your account first: run /discord link in Minecraft, then /link code:<code> here."

        fun roots(): List<MinestomCommand> =
            Nodes.commandRoots() + MinecraftServer.getCommandManager().commands.filter { it.name in Punishments.commandNames }

        fun discordName(command: MinestomCommand): String =
            when (command.name) {
                "t" -> "town"
                "n" -> "nation"
                else -> command.name
            }
    }
}

internal suspend fun <T> onGameThread(action: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val task =
            ModuleScheduler.scheduleNextTick {
                // A timed-out or unloaded request must not execute later when the tick loop resumes.
                if (continuation.isActive) continuation.resumeWith(runCatching(action))
            }
        continuation.invokeOnCancellation { task.cancel() }
    }
