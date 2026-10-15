package net.aechronis.discord

import dev.kord.common.Color
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.createEphemeralFollowup
import dev.kord.core.behavior.interaction.response.edit
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.behavior.interaction.suggestString
import dev.kord.core.entity.channel.CategorizableChannel
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.ComponentInteraction
import dev.kord.core.entity.interaction.InteractionCommand
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.interaction.AutoCompleteInteractionCreateEvent
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.SelectMenuInteractionCreateEvent
import dev.kord.core.on
import dev.kord.core.supplier.EntitySupplyStrategy
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.rest.builder.component.option
import dev.kord.rest.builder.interaction.ChatInputCreateBuilder
import dev.kord.rest.builder.interaction.attachment
import dev.kord.rest.builder.interaction.integer
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.actionRow
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
import net.aechronis.gems.Gems
import java.net.URLEncoder
import java.nio.file.Files

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
                System
                    .getenv("DISCORD_PASTE_LOG_WEBHOOK")
                    ?.trim()
                    ?.takeIf {
                        it.startsWith("https://discord.com/api/webhooks/")
                    }?.let { PasteLog(it, http, scope) }
            val slots = Semaphore(8)
            val names =
                nodes.names + setOf("link", "unlink", "account") +
                    setOfNotNull("paste".takeIf { pastes != null }, "repaste".takeIf { pastes != null })
            if (pastes != null) {
                client.on<SelectMenuInteractionCreateEvent>(scope = scope) {
                    if (interaction.componentId.startsWith("pk:")) handlePicker(pastes, interaction, requests, interaction.values)
                }
                client.on<ButtonInteractionCreateEvent>(scope = scope) {
                    if (interaction.componentId.startsWith("pk:")) {
                        handlePicker(pastes, interaction, requests)
                        return@on
                    }
                    val parts = interaction.componentId.split(":", limit = 2)
                    if (parts.size != 2 || parts[0] !in setOf("paste-approve", "paste-reject")) return@on
                    if (!requests.claim(interaction.id.toString())) return@on
                    try {
                        val response = interaction.deferEphemeralResponse()
                        val role = pastes.staffRole
                        if (role != null &&
                            role !in
                            interaction.data.member.value
                                ?.roles
                                .orEmpty()
                        ) {
                            response.respond { content = "Only staff can approve or reject pastes." }
                            return@on
                        }
                        val result =
                            if (parts[0] == "paste-approve") {
                                pastes.approve(parts[1], interaction.user.id.toString())
                            } else {
                                if (pastes.reject(
                                        parts[1],
                                        interaction.user.id.toString(),
                                    )
                                ) {
                                    "Paste rejected."
                                } else {
                                    "That order was already handled or expired."
                                }
                            }
                        val done = result.startsWith("Pasted") || result.startsWith("Repasted")
                        val outcome =
                            if (parts[0] == "paste-reject") {
                                "rejected"
                            } else if (done) {
                                "completed"
                            } else {
                                "cancelled"
                            }
                        val old = interaction.message.embeds.firstOrNull()
                        val viewer = pastes.viewerOf.remove(parts[1])
                        interaction.message.edit {
                            allowedMentions { }
                            embed {
                                old?.apply(this)
                                title = "${old?.title?.substringBefore(' ') ?: "Paste"} $outcome"
                                color = Color(if (done) 0x57F287 else 0xED4245)
                                field(
                                    if (parts[0] ==
                                        "paste-approve"
                                    ) {
                                        "Approved by"
                                    } else {
                                        "Rejected by"
                                    },
                                    true,
                                ) { "<@${interaction.user.id}> (${interaction.user.username})" }
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
                    var pickerDraft: PasteOrders.Draft? = null
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
                                        "paste" -> submitPaste(pastes!!, http, interaction, command, repaste = false) { pickerDraft = it }
                                        "repaste" -> submitPaste(pastes!!, http, interaction, command, repaste = true) { pickerDraft = it }
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
                    pickerDraft?.let { draft ->
                        val balance = withContext(Dispatchers.IO) { Gems.balance(draft.player) }
                        val png = withContext(Dispatchers.Default) { draft.map.png(draft.selected, draft.paid, draft.blocked) }
                        response.respond {
                            allowedMentions { }
                            addFile("chunks.png", ChannelProvider { ByteReadChannel(png) })
                            picker(draft, balance, null)
                        }
                        return@on
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
                        "repaste" -> "Replace an earlier paste with a new schematic; extra chunks cost 50 gems each"
                        "paste" -> "Buy a schematic paste in your town and pick the chunks (50 gems per chunk)"
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
    onDraft: (PasteOrders.Draft) -> Unit,
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
    val draft =
        pastes
            .prepare(http, interaction.user.id.toString(), file.filename, file.size, file.url, x, y, z, replacing)
            .getOrElse { return CommandReply(it.message ?: "Could not process that schematic.") }
    onDraft(draft)
    return CommandReply("")
}

/** Chunks that can be picked, in reading order, split into dropdowns of 25 (Discord's limit); at most four fit. */
private fun chunkGroups(draft: PasteOrders.Draft): List<List<Long>> =
    (draft.map.filled - draft.blocked)
        .sortedWith(compareBy({ ChunkMap.cz(it) }, { ChunkMap.cx(it) }))
        .chunked(25)
        .take(4)

/** The picker: the render, a cost summary, a dropdown per 25 chunks and the action buttons. */
private fun MessageBuilder.picker(
    draft: PasteOrders.Draft,
    balance: Long?,
    note: String?,
) {
    val map = draft.map
    val groups = chunkGroups(draft)
    val added = (draft.selected - draft.paid).size
    val cost = added * PasteOrders.COST_PER_CHUNK
    embed {
        title = if (draft.old != null) "Repaste: choose chunks" else "Choose chunks to paste"
        description =
            buildString {
                append("Pick the chunks to paste from the menu below. Green is selected, blue was already paid for.")
                if (draft.blocked.isNotEmpty()) {
                    append(
                        "\nRed chunks (${draft.blocked.joinToString(", ") { map.label(it) }}) are outside your town and can't be pasted.",
                    )
                }
                if (groups.sumOf { it.size } < draft.touched.size - draft.blocked.size) {
                    append("\nThis build has too many chunks to pick one by one, so only the first ${groups.sumOf { it.size }} are listed.")
                }
                if (note != null) append("\n\n⚠️ **$note**")
            }
        color = Color(if (note != null) 0xED4245 else 0x5865F2)
        field("Selected", true) { "${draft.selected.size} of ${draft.touched.size - draft.blocked.size} chunks" }
        field("Cost", true) { if (cost > 0) "$cost gems ($added new)" else "Free" }
        if (balance != null) field("Your gems", true) { "$balance" }
        image = "attachment://chunks.png"
        footer { text = "Origin ${draft.x} ${draft.y} ${draft.z}" }
    }
    components = mutableListOf()
    groups.forEachIndexed { index, group ->
        actionRow {
            stringSelect("pk:${draft.id}:sel:$index") {
                placeholder = "Chunks ${map.label(group.first())} to ${map.label(group.last())}"
                allowedValues = 0..group.size
                for (chunk in group) {
                    val x = ChunkMap.cx(chunk) shl 4
                    val z = ChunkMap.cz(chunk) shl 4
                    val paid = if (chunk in draft.paid) " · already paid" else ""
                    option(map.label(chunk), chunk.toString()) {
                        description = "x $x..${x + 15}, z $z..${z + 15}$paid"
                        default = chunk in draft.selected
                    }
                }
            }
        }
    }
    actionRow {
        interactionButton(ButtonStyle.Success, "pk:${draft.id}:ok") { label = "Confirm" }
        interactionButton(ButtonStyle.Secondary, "pk:${draft.id}:all") { label = "Select all" }
        interactionButton(ButtonStyle.Secondary, "pk:${draft.id}:clear") { label = "Clear" }
        interactionButton(ButtonStyle.Danger, "pk:${draft.id}:cancel") { label = "Cancel" }
    }
}

private suspend fun handlePicker(
    pastes: PasteOrders,
    interaction: ComponentInteraction,
    requests: InteractionRequests,
    values: List<String> = emptyList(),
) {
    if (!requests.claim(interaction.id.toString())) return
    try {
        val parts = interaction.componentId.split(":")
        println("[Discord] Picker ${parts.getOrNull(2)}")
        val draft = pastes.draft(parts.getOrNull(1).orEmpty())
        if (draft == null) {
            interaction.respondEphemeral { content = "This picker expired. Run the command again." }
            return
        }
        if (draft.discordId != interaction.user.id.toString()) {
            interaction.respondEphemeral { content = "This picker belongs to someone else." }
            return
        }
        // Confirming uploads to the staff channel, which can outlast Discord's 3 second reply window.
        val response = interaction.deferEphemeralMessageUpdate()
        var note: String? = null
        val map = draft.map
        synchronized(draft) {
            when (parts.getOrNull(2)) {
                "sel" -> {
                    val group = chunkGroups(draft).getOrNull(parts.getOrNull(3)?.toIntOrNull() ?: -1).orEmpty().toSet()
                    draft.selected.removeAll(group)
                    draft.selected += values.mapNotNull { it.toLongOrNull() }.filter { it in group }
                }
                "all" -> draft.selected += draft.touched - draft.blocked
                "clear" -> draft.selected.clear()
            }
        }
        when (parts.getOrNull(2)) {
            "cancel" -> {
                pastes.cancel(draft.id)
                response.edit {
                    content = "Cancelled."
                    embeds = mutableListOf()
                    components = mutableListOf()
                }
                return
            }
            "ok" -> {
                val result = pastes.confirm(draft.id)
                val order = result.getOrNull()
                println("[Discord] Picker confirm ${if (order != null) "accepted" else "refused: ${result.exceptionOrNull()?.message}"}")
                if (order == null) {
                    note = result.exceptionOrNull()?.message ?: "Could not submit that selection."
                } else {
                    pastes.ticketOf[order.id] = interaction.channelId
                    try {
                        postForStaff(pastes, interaction.kord, interaction.channelId, order, draft.map, draft.paid)
                    } catch (error: CancellationException) {
                        pastes.reject(order.id, "system")
                        throw error
                    } catch (error: Exception) {
                        pastes.reject(order.id, "system")
                        System.err.println("[Discord] Could not post paste for staff (${error.javaClass.simpleName})")
                        response.createEphemeralFollowup {
                            content =
                                "Could not post your request for staff; nothing was charged. Run the command again."
                        }
                        return
                    }
                    response.edit {
                        content =
                            "Request posted: ${order.chunks} chunk(s), ${order.newChunks} new for ${order.cost} gems. " +
                            "Waiting for staff approval; gems are charged on approval."
                        embeds = mutableListOf()
                        components = mutableListOf()
                    }
                    return
                }
            }
        }
        val balance = withContext(Dispatchers.IO) { Gems.balance(draft.player) }
        val png = withContext(Dispatchers.Default) { map.png(draft.selected, draft.paid, draft.blocked) }
        response.edit {
            // Drop the previous render so only the current one stays on the message.
            attachments = mutableListOf()
            addFile("chunks.png", ChannelProvider { ByteReadChannel(png) })
            picker(draft, balance, note)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        System.err.println("[Discord] Picker failed (${error.javaClass.simpleName})")
    }
}

private suspend fun postForStaff(
    pastes: PasteOrders,
    kord: Kord,
    ticket: Snowflake,
    order: PasteOrders.Order,
    map: ChunkMap,
    paid: Set<Long>,
) {
    val repaste = order.replaces != null
    val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(order.file) }
    val png = withContext(Dispatchers.Default) { map.png(order.allowance - paid, paid) }
    val review = MessageChannelBehavior(pastes.staffChannel ?: ticket, kord)
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
                field("Chunks", true) { "${order.chunks} (${order.newChunks} new)" }
                field("Cost", true) { if (order.cost > 0) "${order.cost} gems" else "Free" }
                image = "attachment://chunks.png"
                footer { text = "Order ${order.id}" }
            }
            addFile("${order.id}.schem", ChannelProvider { ByteReadChannel(bytes) })
            addFile("chunks.png", ChannelProvider { ByteReadChannel(png) })
            actionRow {
                interactionButton(ButtonStyle.Success, "paste-approve:${order.id}") { label = "Approve" }
                interactionButton(ButtonStyle.Danger, "paste-reject:${order.id}") { label = "Reject" }
            }
        }
    message.attachments.firstOrNull { it.filename.endsWith(".schem") }?.url?.let { cdn ->
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
