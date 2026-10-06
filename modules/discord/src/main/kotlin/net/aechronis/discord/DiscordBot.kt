package net.aechronis.discord

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.createEphemeralFollowup
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.behavior.interaction.suggestString
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.interaction.AutoCompleteInteractionCreateEvent
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.core.supplier.EntitySupplyStrategy
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.rest.builder.interaction.ChatInputCreateBuilder
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.message.allowedMentions
import dev.kord.rest.builder.message.embed
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object DiscordBot {
    suspend fun run(
        token: String,
        guildId: Snowflake?,
        scope: CoroutineScope,
        links: AccountLinks,
        nodes: NodesCommands,
    ) {
        // Explicitly own the engine, including when Kord fails during construction.
        val engine = OkHttp.create()
        val http = HttpClient(engine)
        var kord: Kord? = null
        try {
            val client =
                withTimeoutOrNull(30_000) { Kord(token) { httpClient = http } }
                    ?: error("Discord initialization timed out")
            kord = client
            val requests = InteractionRequests()
            val slots = Semaphore(8)
            val names = nodes.names + setOf("link", "unlink", "account")
            client.on<ReadyEvent>(scope = scope) {
                println("[Discord] Connected; ${nodes.names.size} Nodes command families are available")
            }
            client.on<AutoCompleteInteractionCreateEvent>(scope = scope) {
                if (interaction.command.rootName !in nodes.names) return@on
                if (guildId != null && interaction.data.guildId.value != guildId) return@on
                try {
                    val choices =
                        if (slots.tryAcquire()) {
                            try {
                                withTimeoutOrNull(1_500) {
                                    nodes.suggest(
                                        interaction.user.id.toString(),
                                        interaction.command.rootName,
                                        interaction.focusedOption.value,
                                    )
                                }.orEmpty()
                            } finally {
                                slots.release()
                            }
                        } else {
                            emptyList()
                        }
                    interaction.suggestString { choices.forEach { choice(it, it) } }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Suggestions are optional; never disclose parser internals or account data.
                    runCatching { interaction.suggestString { } }
                }
            }
            client.on<ChatInputCommandInteractionCreateEvent>(scope = scope) {
                val command = interaction.command
                if (command.rootName !in names) return@on
                if (guildId != null && interaction.data.guildId.value != guildId) return@on
                if (!requests.claim(interaction.id.toString())) return@on
                try {
                    val response = interaction.deferEphemeralResponse()
                    val acquired = slots.tryAcquire()
                    val reply =
                        if (!acquired) {
                            CommandReply("The bot is busy. Your command was not run; try again shortly.")
                        } else {
                            try {
                                withTimeoutOrNull(15_000) {
                                    val id = interaction.user.id.toString()
                                    when (command.rootName) {
                                        "link" -> CommandReply(links.link(id, command.strings["code"].orEmpty()))
                                        "unlink" -> {
                                            val removed = links.unlinkDiscord(id)
                                            CommandReply(if (removed) "Minecraft account unlinked." else "No Minecraft account is linked.")
                                        }
                                        "account" ->
                                            CommandReply(
                                                links.player(id)?.let { "Linked Minecraft UUID: $it. Use /unlink to revoke access." }
                                                    ?: "Not linked. Run /discord link in Minecraft, then /link code:<code> here.",
                                            )
                                        else -> nodes.execute(id, command.rootName, command.strings["arguments"].orEmpty())
                                    }
                                } ?: CommandReply("The request timed out. It may have run; check its state before retrying.")
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                System.err.println("[Discord] Command failed (${error.javaClass.simpleName})")
                                CommandReply("The request failed. It may have partially run; check its state before retrying.")
                            } finally {
                                slots.release()
                            }
                        }
                    val pages = reply.pages()
                    val attachment = pages.size > 6
                    val message =
                        response.respond {
                            allowedMentions { }
                            if (attachment) {
                                addFile(
                                    "nodes-output.txt",
                                    ChannelProvider { ByteReadChannel(reply.lines.joinToString("\n").toByteArray(Charsets.UTF_8)) },
                                )
                            } else {
                                embed { description = pages.firstOrNull() ?: "Command completed." }
                            }
                        }
                    if (!attachment) {
                        pages.drop(1).forEach { page ->
                            message.createEphemeralFollowup {
                                allowedMentions { }
                                embed { description = page }
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Discord exceptions can contain request details; never log tokens or bodies.
                    System.err.println("[Discord] Response failed (${error.javaClass.simpleName})")
                }
            }
            for (name in names) {
                val description =
                    when (name) {
                        "link" -> "Link your Minecraft account using an in-game /discord link code"
                        "unlink" -> "Revoke this Discord account's access to your Minecraft account"
                        "account" -> "Show your linked Minecraft account"
                        else -> "Run the in-game /$name command as your linked player"
                    }
                val options: ChatInputCreateBuilder.() -> Unit = {
                    when (name) {
                        "link" ->
                            string("code", "Private code from /discord link in Minecraft") {
                                required = true
                                minLength = AccountLinks.CODE_LENGTH
                                maxLength = AccountLinks.CODE_LENGTH
                            }
                        "unlink", "account" -> Unit
                        else ->
                            string("arguments", "Everything after /$name in Minecraft; leave empty for its default command") {
                                autocomplete = true
                                maxLength = 1800
                            }
                    }
                }
                if (guildId == null) {
                    client.createGlobalChatInputCommand(name, description, options)
                } else {
                    client.createGuildChatInputCommand(guildId, name, description, options)
                }
            }
            // Remove the overview registered by earlier module versions in this command scope.
            val applicationId = client.resources.applicationId
            val registered = client.with(EntitySupplyStrategy.rest)
            if (guildId == null) {
                registered.getGlobalApplicationCommands(applicationId).collect { if (it.name == "stats") it.delete() }
            } else {
                registered.getGuildApplicationCommands(applicationId, guildId).collect { if (it.name == "stats") it.delete() }
            }
            val commandScope = guildId?.let { "in guild $it" } ?: "globally"
            println("[Discord] Registered ${names.size} slash commands, including /link, $commandScope")
            client.login { intents = Intents(Intent.Guilds) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            System.err.println("[Discord] Bot stopped (${error.javaClass.simpleName}); check configuration and restart the discord module")
        } finally {
            scope.cancel()
            withContext(NonCancellable) {
                try {
                    kord?.shutdown()
                    kord?.coroutineContext?.job?.cancelAndJoin()
                    kord
                        ?.gateway
                        ?.gateways
                        ?.values
                        ?.forEach { it.coroutineContext.job.cancelAndJoin() }
                } finally {
                    // Kord.shutdown detaches the gateway but does not close its HTTP client.
                    kord?.resources?.httpClient?.close()
                    http.close()
                    engine.close()
                }
            }
        }
    }
}

/** Gateway redelivery must never repeat a state-changing command. */
internal class InteractionRequests(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val seen = linkedMapOf<String, Long>()

    @Synchronized
    fun claim(id: String): Boolean {
        val time = now()
        seen.entries.removeIf { it.value <= time }
        if (id in seen || seen.size >= 10_000) return false
        seen[id] = time + 15 * 60_000
        return true
    }
}
