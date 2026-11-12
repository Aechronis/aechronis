package net.aechronis.discord

import dev.kord.common.Color
import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.TextInputStyle
import dev.kord.core.Kord
import dev.kord.core.behavior.MessageBehavior
import dev.kord.core.behavior.channel.MessageChannelBehavior
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.behavior.interaction.modal
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.behavior.interaction.response.edit
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.core.event.interaction.ModalSubmitInteractionCreateEvent
import dev.kord.core.event.interaction.SelectMenuInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.component.option
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.actionRow
import dev.kord.rest.builder.message.allowedMentions
import dev.kord.rest.builder.message.embed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.aechronis.punishments.DiscordInfo
import net.aechronis.punishments.Evidence
import net.aechronis.punishments.Punishments
import net.aechronis.punishments.Record
import net.aechronis.punishments.Staff
import net.aechronis.punishments.Type
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives the punishments website linked Discord accounts. With `DISCORD_PUNISHMENT_LOG_CHANNEL_ID` set, the bot
 * posts every punishment there and keeps the post current. Its buttons lift the punishment or add clip links,
 * for linked staff whose Minecraft account has the matching permission.
 * Close it when the bot stops.
 */
internal class PunishmentBridge(
    private val links: AccountLinks,
    private val kord: Kord,
    private val scope: CoroutineScope,
    private val channel: Snowflake?,
    /** Whether the Minecraft account has the LuckPerms node, loading it if offline. */
    private val allowed: suspend (UUID, String) -> Boolean,
) : AutoCloseable {
    private val usernames = ConcurrentHashMap<String, String>()
    private val lookup: (UUID) -> DiscordInfo? = { uuid -> links.discord(uuid)?.let { DiscordInfo(it, username(it)) } }
    private val listener: (Record) -> Unit = { record -> scope.launch { guarded("log") { post(record) } } }
    private val evidenceListener: (List<Record>) -> Unit = { records -> scope.launch { guarded("evidence log") { postEvidence(records) } } }
    private val requests = InteractionRequests()

    /** Log post per record id, so lifts and evidence edit it. Older posts are looked up again after a restart. */
    private val posts = ConcurrentHashMap<Long, Snowflake>()
    private val handlers = mutableListOf<Job>()

    init {
        Punishments.discordLookup = lookup
        if (channel != null) {
            Punishments.listeners += listener
            Punishments.evidenceListeners += evidenceListener
        }
        handlers += kord.on<ButtonInteractionCreateEvent>(scope = scope) { onButton(this) }
        handlers += kord.on<ModalSubmitInteractionCreateEvent>(scope = scope) { onModal(this) }
        handlers += kord.on<SelectMenuInteractionCreateEvent>(scope = scope) { onEvidence(this) }
    }

    /** Blocking; called from the website's request threads, never the tick thread. */
    private fun username(id: String): String? =
        usernames[id] ?: runBlocking {
            withTimeoutOrNull(3_000) { kord.getUser(Snowflake(id))?.username }
        }?.also { usernames[id] = it }

    private suspend fun post(record: Record) {
        val log = channel ?: return
        val original = if (record.revokedAt != null) find(log, record.id) else null
        if (original != null) {
            update(log, original, record)
            return
        }
        val message =
            MessageChannelBehavior(log, kord).createMessage {
                allowedMentions { }
                embed { describe(record) }
                buttons(record)
            }
        if (record.revokedAt == null) posts[record.id] = message.id
    }

    /** The record's log post: remembered, or found among the bot's last 100 posts after a restart. */
    private suspend fun find(
        log: Snowflake,
        id: Long,
    ): Snowflake? =
        posts[id] ?: MessageChannelBehavior(log, kord)
            .getMessagesBefore(Snowflake.max, 100)
            .firstOrNull {
                it.author?.id == kord.selfId &&
                    it.embeds
                        .firstOrNull()
                        ?.footer
                        ?.text == "Record #$id"
            }?.id
            ?.also { posts[id] = it }

    private suspend fun update(
        log: Snowflake,
        message: Snowflake,
        record: Record,
    ) = MessageBehavior(log, message, kord).edit {
        embeds = mutableListOf()
        embed { describe(record) }
        components = mutableListOf()
        buttons(record)
    }

    private fun MessageBuilder.buttons(record: Record) =
        actionRow {
            // Warnings never count as active, but can still be lifted.
            if (record.revokedAt == null && (record.active() || record.type == Type.WARN)) {
                interactionButton(ButtonStyle.Danger, "$LIFT${record.id}") { label = "Lift" }
            }
            interactionButton(ButtonStyle.Secondary, "$ADD_EVIDENCE${record.id}") { label = "Add evidence" }
        }

    private fun EmbedBuilder.describe(record: Record) {
        val lifted = record.revokedAt != null
        title = if (lifted) "${record.type.label} lifted" else record.type.label
        color =
            Color(
                if (lifted) {
                    0x57F287
                } else if (record.type == Type.WARN) {
                    0xF0B232
                } else {
                    0xED4245
                },
            )
        field("Player", true) { "${record.name}\n`${record.uuid}`" }
        links.discord(record.uuid)?.let { field("Discord", true) { "<@$it>" } }
        record.category?.let { field("Category", true) { it.label } }
        field("Reason") { record.reason.ifBlank { "-" }.take(1024) }
        if (!lifted && record.type != Type.WARN) field("Expires", true) { record.expiresAt?.let { "<t:${it / 1000}:R>" } ?: "Never" }
        field("Staff", true) { record.staffName }
        record.evidence?.let { field("Evidence") { it.take(1024) } }
        record.revokedBy?.let { field("Lifted by", true) { it } }
        record.revokeReason?.let { field("Lift reason") { it.take(1024) } }
        footer { text = "Record #${record.id}" }
    }

    /** Evidence updates the punishment's own post; only posts too old to find get a separate message. */
    private suspend fun postEvidence(records: List<Record>) {
        val log = channel ?: return
        val unknown = records.filter { record -> find(log, record.id)?.let { update(log, it, record) } == null }
        val first = unknown.firstOrNull() ?: return
        MessageChannelBehavior(log, kord).createMessage {
            allowedMentions { }
            embed {
                title = "Evidence"
                color = Color(0x5865F2)
                description = first.evidence.orEmpty().take(4000)
                field("Player", true) { first.name }
                footer { text = "Record ${unknown.joinToString { "#${it.id}" }}" }
            }
        }
    }

    /** Why the Discord user can't use [action] on record [id], or null when their linked account may. */
    private suspend fun refusal(
        user: Snowflake,
        action: String,
        id: Long,
    ): String? {
        val uuid = links.player(user.toString()) ?: return "Link your Minecraft account first: /discord link in game."
        // Same nodes as the commands: /evidence, or the command that issues and lifts this type.
        val permission =
            if (action == ADD_EVIDENCE) {
                "punishments.punish"
            } else {
                when (Punishments.record(id)?.type ?: return "Record #$id doesn't exist.") {
                    Type.WARN -> "punishments.warn"
                    Type.MUTE -> "punishments.mute"
                    Type.BAN, Type.ITERATION_BAN, Type.IP_BAN -> "punishments.ban"
                    Type.BLACKLIST -> "punishments.blacklist"
                }
            }
        return if (allowed(uuid, permission)) null else "You don't have permission to do that."
    }

    private suspend fun onButton(event: ButtonInteractionCreateEvent) {
        val interaction = event.interaction
        val action = interaction.componentId.substringBefore(':', "") + ":"
        if (action != LIFT && action != ADD_EVIDENCE) return
        val id = interaction.componentId.substringAfter(':').toLongOrNull() ?: return
        if (!requests.claim(interaction.id.toString())) return
        guarded("button") {
            refusal(interaction.user.id, action, id)?.let {
                interaction.respondEphemeral { content = it }
                return@guarded
            }
            if (action == LIFT) {
                interaction.modal("Lift record #$id", "$LIFT$id") {
                    actionRow {
                        textInput(TextInputStyle.Paragraph, "reason", "Reason") {
                            required = false
                            allowedLength = 0..512
                        }
                    }
                }
            } else {
                interaction.modal("Evidence for record #$id", "$ADD_EVIDENCE$id") {
                    actionRow {
                        textInput(TextInputStyle.Paragraph, "links", "Clip links, one per line") {
                            placeholder = "https://medal.tv/..."
                            allowedLength = 8..1000
                        }
                    }
                }
            }
        }
    }

    private suspend fun onModal(event: ModalSubmitInteractionCreateEvent) {
        val interaction = event.interaction
        val action = interaction.modalId.substringBefore(':', "") + ":"
        if (action != LIFT && action != ADD_EVIDENCE) return
        val id = interaction.modalId.substringAfter(':').toLongOrNull() ?: return
        if (!requests.claim(interaction.id.toString())) return
        guarded("form") {
            val response = interaction.deferEphemeralResponse()
            refusal(interaction.user.id, action, id)?.let {
                response.respond { content = it }
                return@guarded
            }
            val input = { name: String ->
                interaction.textInputs[name]
                    ?.value
                    ?.trim()
                    .orEmpty()
            }
            val reply =
                if (action == LIFT) {
                    val user = interaction.user
                    val staff = Staff(links.player(user.id.toString()), user.username.take(32))
                    val lifted = Punishments.liftRecord(id, staff, input("reason").ifEmpty { "Lifted from Discord" })
                    if (lifted.isEmpty()) "Record #$id was already lifted or has expired." else "Lifted record #$id."
                } else {
                    val clips = input("links").lines().map(String::trim).filter { it.startsWith("https://") || it.startsWith("http://") }
                    when {
                        clips.isEmpty() -> "Add at least one http(s) link."
                        Punishments.addEvidence(id, clips.joinToString("\n")).isEmpty() -> "Record #$id doesn't exist."
                        else -> "Added ${clips.size} link(s) to record #$id."
                    }
                }
            response.respond { content = reply }
        }
    }

    private suspend fun onEvidence(event: SelectMenuInteractionCreateEvent) {
        val interaction = event.interaction
        val offence = interaction.componentId.removePrefix("punish-evidence:").takeIf { it != interaction.componentId } ?: return
        if (!requests.claim(interaction.id.toString())) return
        guarded("evidence") {
            val response = interaction.deferEphemeralMessageUpdate()
            val uuid = links.player(interaction.user.id.toString())
            val count = uuid?.let { Evidence.attach(it, offence, interaction.values.mapNotNull(String::toIntOrNull)) } ?: 0
            response.edit {
                content = if (count > 0) "Attached $count message(s) as evidence." else "This picker expired."
                components = mutableListOf()
            }
        }
    }

    /** Missing permissions or a deleted channel must never break punishments. */
    private suspend fun guarded(
        what: String,
        action: suspend () -> Unit,
    ) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            System.err.println("[Discord] Punishment $what failed (${error.javaClass.simpleName})")
        }
    }

    override fun close() {
        if (Punishments.discordLookup === lookup) Punishments.discordLookup = null
        Punishments.listeners.remove(listener)
        Punishments.evidenceListeners.remove(evidenceListener)
        handlers.forEach { it.cancel() }
    }

    private companion object {
        const val LIFT = "punish-lift:"
        const val ADD_EVIDENCE = "punish-add-evidence:"
    }
}

/** Lets staff attach the player's recent chat after punishing from Discord. */
internal fun MessageBuilder.evidencePicker(pick: Evidence.Pick) {
    content = "Attach chat evidence for ${pick.target}? Pick the messages, or dismiss this to skip."
    actionRow {
        stringSelect("punish-evidence:${pick.offence}") {
            placeholder = "Recent messages, newest first"
            allowedValues = 1..pick.messages.size
            pick.messages.forEachIndexed { index, message ->
                option(message.text.take(100), index.toString()) { description = message.time }
            }
        }
    }
}
