package net.aechronis.discord

import dev.kord.common.Color
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Permission
import dev.kord.common.entity.Permissions
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.response.createEphemeralFollowup
import dev.kord.core.behavior.interaction.response.edit
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.behavior.interaction.suggestString
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.InteractionCommand
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.interaction.AutoCompleteInteractionCreateEvent
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.core.supplier.EntitySupplyStrategy
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.rest.builder.interaction.ChatInputCreateBuilder
import dev.kord.rest.builder.interaction.attachment
import dev.kord.rest.builder.interaction.string
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
import net.aechronis.punishments.Punishments
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
        punishmentLog: Snowflake? = null,
        adminRole: Snowflake? = null,
        schems: SchemUpload? = null,
    ) {
        // Explicitly own the engine, including when Kord fails during construction.
        val engine = OkHttp.create()
        val http = HttpClient(engine)
        var kord: Kord? = null
        var punishments: PunishmentBridge? = null
        var web: PasteWeb? = null
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
            punishments = PunishmentBridge(links, client, scope, punishmentLog, adminRole, nodes::allowed)
            val slots = Semaphore(8)
            // /schemupload lives in whichever server holds its channel, which may be a separate staff server.
            val schemGuild =
                schems?.let {
                    runCatching {
                        client.rest.channel
                            .getChannel(it.channel)
                            .guildId.value
                    }.getOrNull()
                        ?: null.also { System.err.println("[Discord] /schemupload channel not found; is the bot in that server?") }
                } ?: guildId
            val names =
                nodes.names + setOf("link", "unlink", "account") +
                    setOfNotNull("paste".takeIf { pastes != null }, "schemupload".takeIf { schems != null })
            if (pastes != null) {
                web =
                    try {
                        PasteWeb.start(pastes.webPort, pastes) { order, draft, placement ->
                            pastes.ticketOf[order.id] = draft.channel
                            postForStaff(pastes, client, order, placement.map, draft.paid intersect order.allowance)
                        }
                    } catch (error: Exception) {
                        System.err.println(
                            "[Discord] Paste picker could not listen on port ${pastes.webPort} (${error.javaClass.simpleName})",
                        )
                        null
                    }
                client.on<ButtonInteractionCreateEvent>(scope = scope) {
                    val parts = interaction.componentId.split(":", limit = 2)
                    if (parts.size != 2 || parts[0] !in setOf("paste-approve", "paste-reject")) return@on
                    if (!requests.claim(interaction.id.toString())) return@on
                    try {
                        val response = interaction.deferEphemeralResponse()
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
                        val done = result.startsWith("Pasted")
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
                if (interaction.command.rootName in Punishments.commandNames) {
                    val focused =
                        interaction.command.options.entries
                            .firstOrNull { it.value.focused }
                            ?.key
                            .orEmpty()
                    val choices =
                        runCatching {
                            PunishmentOptions.suggest(focused, interaction.focusedOption.value)
                        }
                    runCatching {
                        interaction.suggestString {
                            choices.getOrDefault(emptyList()).forEach { (label, value) ->
                                choice(label, value)
                            }
                        }
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
                val home = if (command.rootName == "schemupload") schemGuild else guildId
                if (home != null && interaction.data.guildId.value != home) return@on
                if (!requests.claim(interaction.id.toString())) return@on
                try {
                    val response = interaction.deferEphemeralResponse()
                    var pickerLink: String? = null
                    var landLink = false
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
                                        "paste" ->
                                            submitPaste(pastes!!, http, interaction, command) { link, land ->
                                                pickerLink = link
                                                landLink = land
                                            }
                                        "schemupload" -> {
                                            val file = command.attachments["schematic"]
                                            when {
                                                interaction.channelId != schems!!.channel ->
                                                    CommandReply("Use this in <#${schems.channel}>.")
                                                file == null -> CommandReply("Attach a .schem file.")
                                                else ->
                                                    CommandReply(
                                                        schems.upload(
                                                            http,
                                                            "${interaction.user.username} ($id)",
                                                            file.filename,
                                                            file.size,
                                                            file.url,
                                                            command.strings["name"],
                                                        ),
                                                    )
                                            }
                                        }
                                        in Punishments.commandNames ->
                                            PunishmentOptions.problem(command)?.let { CommandReply(it) }
                                                ?: nodes.execute(id, command.rootName, PunishmentOptions.arguments(command))
                                        else -> nodes.execute(id, command.rootName, command.strings["arguments"].orEmpty())
                                    }
                                } ?: CommandReply("The request timed out. It may have run; check its state before retrying.")
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                System.err.println("[Discord] Command failed (${error.javaClass.simpleName}: ${error.message})")
                                CommandReply("The request failed. It may have partially run; check its state before retrying.")
                            } finally {
                                slots.release()
                            }
                        }
                    pickerLink?.let { link ->
                        response.respond {
                            allowedMentions { }
                            embed {
                                title = if (landLink) "Your paid chunks" else "Choose where to paste"
                                description =
                                    (
                                        if (landLink) {
                                            "Open the map to see every chunk you've paid for. "
                                        } else {
                                            "Schematic accepted. Open the picker to set the coordinates, rotation and chunks; " +
                                                "chunks you already paid for are free. "
                                        }
                                    ) + "The link is yours alone and works for 15 minutes; don't share it."
                                color = Color(0x5865F2)
                            }
                            actionRow { linkButton(link) { label = if (landLink) "Open the map" else "Open the chunk picker" } }
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
                        "paste" -> "Paste a schematic (${PasteOrders.COST_PER_CHUNK} gems per new chunk); no file shows your paid chunks"
                        "schemupload" -> "Upload a .schem to the server for //schem load in-game (upload channel only)"
                        "unlink" -> "Revoke this Discord account's access to your Minecraft account"
                        "account" -> "Show your linked Minecraft account"
                        in Punishments.commandNames -> "Staff: run the in-game /$name command as your linked account"
                        else -> "Run the in-game /$name command as your linked player"
                    }
                val options: ChatInputCreateBuilder.() -> Unit = {
                    // Hidden from regular members by default; the in-game permission is still checked.
                    if (name in Punishments.commandNames) defaultMemberPermissions = Permissions(Permission.ModerateMembers)
                    when (name) {
                        "link" ->
                            string("code", "Private code from /discord link in Minecraft") {
                                required = true
                                minLength = AccountLinks.CODE_LENGTH
                                maxLength = AccountLinks.CODE_LENGTH
                            }
                        "paste" ->
                            attachment("schematic", "Sponge .schem file; leave empty to see the chunks you paid for") {
                                required = false
                            }
                        "schemupload" -> {
                            attachment("schematic", "Sponge .schem file") { required = true }
                            string("name", "Name for //schem load; defaults to the file name") { maxLength = 48 }
                        }
                        "unlink", "account" -> Unit
                        in Punishments.commandNames -> PunishmentOptions.register(this, name)
                        else ->
                            string("arguments", "Everything after /$name in Minecraft; leave empty for its default command") {
                                autocomplete = true
                                maxLength = 1800
                            }
                    }
                }
                val target = if (name == "schemupload") schemGuild else guildId
                if (target == null) {
                    client.createGlobalChatInputCommand(name, description, options)
                } else {
                    client.createGuildChatInputCommand(target, name, description, options)
                }
            }
            // Remove commands registered by earlier module versions in this command scope.
            val applicationId = client.resources.applicationId
            val registered = client.with(EntitySupplyStrategy.rest)
            val retired = setOf("stats", "repaste") + setOfNotNull("schemupload".takeIf { schemGuild != guildId })
            if (guildId == null) {
                registered.getGlobalApplicationCommands(applicationId).collect { if (it.name in retired) it.delete() }
            } else {
                registered.getGuildApplicationCommands(applicationId, guildId).collect { if (it.name in retired) it.delete() }
            }
            val commandScope = guildId?.let { "in guild $it" } ?: "globally"
            println("[Discord] Registered ${names.size} slash commands, including /link, $commandScope")
            if (schems != null && schemGuild != guildId) println("[Discord] /schemupload registered in guild $schemGuild")
            client.login { intents = Intents(Intent.Guilds) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            System.err.println("[Discord] Bot stopped (${error.javaClass.simpleName}); check configuration and restart the discord module")
        } finally {
            punishments?.close()
            web?.close()
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
    onLink: (link: String, land: Boolean) -> Unit,
): CommandReply {
    val file = command.attachments["schematic"]
    if (file == null) {
        val land =
            pastes
                .prepareLand(interaction.user.id.toString())
                .getOrElse { return CommandReply(it.message ?: "Could not draw your map.") }
        onLink(pastes.webUrl + land.id, true)
        return CommandReply("")
    }
    val draft =
        pastes
            .prepare(http, interaction.user.id.toString(), file.filename, file.size, file.url, interaction.channelId)
            .getOrElse { return CommandReply(it.message ?: "Could not process that schematic.") }
    onLink(pastes.webUrl + draft.id, false)
    return CommandReply("")
}

private suspend fun postForStaff(
    pastes: PasteOrders,
    kord: Kord,
    order: PasteOrders.Order,
    map: ChunkMap,
    paid: Set<Long>,
) {
    val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(order.file) }
    val png = withContext(Dispatchers.Default) { map.png(order.allowance - paid, paid) }
    val review = MessageChannelBehavior(pastes.staffChannel, kord)
    val message =
        review.createMessage {
            allowedMentions { }
            embed {
                title = "Paste request"
                description = "Open the 3D viewer and check the build for offensive content before approving."
                color = Color(0xF0B232)
                field("Player", true) { "<@${order.discordId}>" }
                field("Town", true) { order.town }
                field("Origin", true) { "`${order.x} ${order.y} ${order.z}`" }
                field("Chunks", true) { "${order.chunks} (${order.newChunks} new)" }
                field("Cost", true) { if (order.cost > 0) "${order.cost} gems" else "Free" }
                if (order.rotation != 0) field("Rotation", true) { "${order.rotation * 90}° clockwise (the file is unrotated)" }
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
