package net.aechronis.discord

import dev.kord.common.Color
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import java.net.URLEncoder
import java.nio.file.Files
import dev.kord.core.behavior.edit
import dev.kord.core.entity.channel.CategorizableChannel
import dev.kord.core.entity.interaction.InteractionCommand
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.rest.builder.interaction.attachment
import dev.kord.rest.builder.interaction.integer
import dev.kord.rest.builder.message.actionRow
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
import kotlinx.coroutines.Dispatchers
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
        pastes: PasteOrders? = null,
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
            pastes?.log =
                System.getenv("DISCORD_PASTE_LOG_WEBHOOK")?.trim()?.takeIf { it.startsWith("https://discord.com/api/webhooks/") }?.let { PasteLog(it, http, scope) }
            val slots = Semaphore(8)
            val names = nodes.names + setOf("link", "unlink", "account") + setOfNotNull("paste".takeIf { pastes != null }, "repaste".takeIf { pastes != null })
            if (pastes != null) {
                client.on<ButtonInteractionCreateEvent>(scope = scope) {
                    val parts = interaction.componentId.split(":", limit = 2)
                    if (parts.size != 2 || parts[0] !in setOf("paste-approve", "paste-reject")) return@on
                    if (!requests.claim(interaction.id.toString())) return@on
                    try {
                        val response = interaction.deferEphemeralResponse()
                        val role = pastes.staffRole
                        if (role != null && role !in interaction.data.member.value?.roles.orEmpty()) {
                            response.respond { content = "Only staff can approve or reject pastes." }
                            return@on
                        }
                        val result =
                            if (parts[0] == "paste-approve") {
                                pastes.approve(parts[1], interaction.user.id.toString())
                            } else {
                                if (pastes.reject(parts[1], interaction.user.id.toString())) "Paste rejected." else "That order was already handled or expired."
                            }
                        val done = result.startsWith("Pasted") || result.startsWith("Repasted")
                        val outcome = if (parts[0] == "paste-reject") "rejected" else if (done) "completed" else "cancelled"
                        val old = interaction.message.embeds.firstOrNull()
                        val viewer = pastes.viewerOf.remove(parts[1])
                        interaction.message.edit {
                            allowedMentions { }
                            embed {
                                old?.apply(this)
                                title = "${old?.title?.substringBefore(' ') ?: "Paste"} $outcome"
                                color = Color(if (done) 0x57F287 else 0xED4245)
                                field(if (parts[0] == "paste-approve") "Approved by" else "Rejected by", true) { "<@${interaction.user.id}> (${interaction.user.username})" }
                                field("Result") { result }
                            }
                            components = mutableListOf()
                            if (viewer != null) actionRow { linkButton(viewer) { label = "Open 3D viewer" } }
                        }
                        val ticket = pastes.ticketOf.remove(parts[1])
                        if (ticket != null && ticket != interaction.channelId) {
                            runCatching {
                                MessageChannelBehavior(ticket, interaction.kord).createMessage {
                                    allowedMentions { }
                                    embed {
                                        title = "Paste $outcome"
                                        description = result
                                        color = Color(if (done) 0x57F287 else 0xED4245)
                                    }
                                }
                            }
                        }
                        response.respond { content = "Done." }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        System.err.println("[Discord] Paste button failed (${error.javaClass.simpleName})")
                    }
                }
            }
            client.on<ReadyEvent>(scope = scope) {
                println("[Discord] Connected; ${nodes.names.size} Nodes command families are available")
            }
            client.on<AutoCompleteInteractionCreateEvent>(scope = scope) {
                if (guildId != null && interaction.data.guildId.value != guildId) return@on
                if (pastes != null && interaction.command.rootName == "repaste") {
                    try {
                        val own = pastes.suggest(interaction.user.id.toString())
                        interaction.suggestString { own.forEach { (label, id) -> choice(label, id) } }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        runCatching { interaction.suggestString { } }
                    }
                    return@on
                }
                if (interaction.command.rootName !in nodes.names) return@on
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
                                        "paste" -> submitPaste(pastes!!, http, interaction, command, repaste = false)
                                        "repaste" -> submitPaste(pastes!!, http, interaction, command, repaste = true)
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
                        "repaste" -> "Replace one of your earlier pastes with an improved schematic, free of charge"
                        "paste" -> "Buy a schematic paste inside your town (50 gems per chunk touched); staff approve in the ticket"
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
                        "repaste" -> {
                            string("paste", "Which of your earlier pastes to replace") {
                                required = true
                                autocomplete = true
                            }
                            attachment("schematic", "Sponge .schem file") { required = true }
                        }
                        "paste" -> {
                            attachment("schematic", "Sponge .schem file") { required = true }
                            integer("x", "Block X where the schematic's origin lands") { required = true }
                            integer("y", "Block Y where the schematic's origin lands") { required = true }
                            integer("z", "Block Z where the schematic's origin lands") { required = true }
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

private suspend fun submitPaste(
    pastes: PasteOrders,
    http: HttpClient,
    interaction: ChatInputCommandInteraction,
    command: InteractionCommand,
    repaste: Boolean,
): CommandReply {
    val inTicket = (interaction.channel.asChannelOrNull() as? CategorizableChannel)?.categoryId == pastes.ticketCategory
    if (!inTicket) return CommandReply("Use this command inside your ticket channel.")
    val file = command.attachments["schematic"] ?: return CommandReply("Attach a .schem file.")
    fun coord(name: String): Int? = command.integers[name]?.takeIf { it in -30_000_000L..30_000_000L }?.toInt()
    val replacing = if (repaste) command.strings["paste"].orEmpty() else null
    // A repaste keeps the original origin, so only /paste takes coordinates.
    val x = if (repaste) 0 else coord("x")
    val y = if (repaste) 0 else coord("y")
    val z = if (repaste) 0 else coord("z")
    if (x == null || y == null || z == null) return CommandReply("Coordinates are out of range.")
    val order =
        pastes
            .prepare(http, interaction.user.id.toString(), file.filename, file.size, file.url, x, y, z, replacing)
            .getOrElse { return CommandReply(it.message ?: "Could not process that schematic.") }
    val ticket = interaction.channelId
    pastes.ticketOf[order.id] = ticket
    try {
        postForStaff(pastes, interaction, order, repaste)
    } catch (error: CancellationException) {
        pastes.reject(order.id, "system")
        throw error
    } catch (error: Exception) {
        pastes.reject(order.id, "system")
        System.err.println("[Discord] Could not post paste for staff (${error.javaClass.simpleName})")
        return CommandReply("Could not post your request for staff; nothing was charged. Try again.")
    }
    return CommandReply(
        if (repaste) {
            "Repaste posted: ${order.chunks} chunk(s), free. Waiting for staff approval."
        } else {
            "Request posted: ${order.chunks} chunk(s), ${order.cost} gems. Waiting for staff approval; gems are charged on approval."
        },
    )
}

private suspend fun postForStaff(
    pastes: PasteOrders,
    interaction: ChatInputCommandInteraction,
    order: PasteOrders.Order,
    repaste: Boolean,
) {
    val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(order.file) }
    val review = pastes.staffChannel?.let { MessageChannelBehavior(it, interaction.kord) } ?: interaction.channel
    val message =
        review.createMessage {
            allowedMentions { }
            embed {
                title = if (repaste) "Repaste request" else "Paste request"
                description = "Open the 3D viewer and check the build for offensive content before approving."
                color = Color(0xF0B232)
                field("Player", true) { "<@${order.discordId}>" }
                field("Town", true) { order.town }
                field("Origin", true) { "`${order.x} ${order.y} ${order.z}`" }
                field("Chunks", true) { "${order.chunks}" }
                field("Cost", true) { if (repaste) "Free (replaces an earlier paste)" else "${order.cost} gems" }
                footer { text = "Order ${order.id}" }
            }
            addFile("${order.id}.schem", ChannelProvider { ByteReadChannel(bytes) })
            actionRow {
                interactionButton(ButtonStyle.Success, "paste-approve:${order.id}") { label = "Approve" }
                interactionButton(ButtonStyle.Danger, "paste-reject:${order.id}") { label = "Reject" }
            }
        }
    message.attachments.firstOrNull()?.url?.let { cdn ->
        val viewer = "https://www.shulkr.com/en/viewer?url=" + URLEncoder.encode(cdn, Charsets.UTF_8)
        pastes.viewerOf[order.id] = viewer
        runCatching {
            message.edit {
                components = mutableListOf()
                actionRow {
                    interactionButton(ButtonStyle.Success, "paste-approve:${order.id}") { label = "Approve" }
                    interactionButton(ButtonStyle.Danger, "paste-reject:${order.id}") { label = "Reject" }
                    linkButton(viewer) { label = "Open 3D viewer" }
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
