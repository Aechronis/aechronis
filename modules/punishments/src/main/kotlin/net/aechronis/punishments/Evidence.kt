package net.aechronis.punishments

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps each player's recent chat in memory, and lets staff pick lines of it as evidence right
 * after a chat punishment: in game by clicking chat lines, from Discord through a select menu.
 */
object Evidence {
    class Message(
        val at: Long,
        val text: String,
    ) {
        val time: String get() = TIME.format(Instant.ofEpochMilli(at))
    }

    /** The messages a staff member can attach to [offence]; indexes refer to [messages], newest first. */
    class Pick(
        val offence: String,
        val target: String,
        val messages: List<Message>,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        internal val selected: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    }

    /** Fits Discord's 25-option select menu with room to spare. */
    private const val KEEP = 18
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    private val chat = ConcurrentHashMap<UUID, ArrayDeque<Message>>()
    private val picks = ConcurrentHashMap<UUID, Pick>()

    internal fun heard(
        uuid: UUID,
        text: String,
    ) {
        val messages = chat.getOrPut(uuid) { ArrayDeque() }
        synchronized(messages) {
            messages.addLast(Message(System.currentTimeMillis(), text))
            while (messages.size > KEEP) messages.removeFirst()
        }
    }

    /** Starts a pick after a chat punishment, or a mute or warning, if the player said anything recently. */
    internal fun offer(
        staff: CommandSender,
        records: List<Record>,
    ) {
        val staffPlayer = staff as? Player ?: return
        val first = records.firstOrNull() ?: return
        if (records.none { it.infraction?.category == Category.CHAT || it.type == Type.MUTE || it.type == Type.WARN }) return
        val messages = chat[first.uuid]?.let { synchronized(it) { it.reversed() } }.orEmpty()
        if (messages.isEmpty()) return
        val pick = Pick(first.offence, first.name, messages)
        picks[staffPlayer.uuid] = pick
        // Discord callers use a disconnected stand-in player and show their own picker.
        if (staffPlayer.isOnline) show(staffPlayer, pick)
    }

    /** The staff member's current pick, if any. */
    fun pick(staff: UUID): Pick? = picks[staff]

    /** Saves the chosen messages on the offence and ends the pick. Returns how many were attached. Blocking. */
    fun attach(
        staff: UUID,
        offence: String,
        indexes: Collection<Int>,
    ): Int {
        val pick = picks[staff]?.takeIf { it.offence == offence } ?: return 0
        picks.remove(staff, pick)
        val lines =
            indexes
                .distinct()
                .sorted()
                .mapNotNull { pick.messages.getOrNull(it) }
                .map { "[${it.time}] ${pick.target}: ${it.text}" }
        if (lines.isNotEmpty()) Punishments.addEvidence(pick.offence, lines.joinToString("\n"))
        return lines.size
    }

    private fun show(
        staff: Player,
        pick: Pick,
    ) {
        staff.sendMessage(Component.text("Attach chat evidence for ${pick.target}? Click messages to select them:", NamedTextColor.GOLD))
        pick.messages.forEachIndexed { index, message ->
            staff.sendMessage(
                Component
                    .text("[+] ", NamedTextColor.GREEN)
                    .append(Component.text(message.text, NamedTextColor.WHITE))
                    .hoverEvent(HoverEvent.showText(Component.text("${message.time}\nClick to select")))
                    .clickEvent(ClickEvent.runCommand("/evidence pick $index")),
            )
        }
        staff.sendMessage(actions())
    }

    private fun actions(): Component =
        Component
            .text("[Attach]", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/evidence attach"))
            .append(Component.text("  "))
            .append(Component.text("[Skip]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/evidence skip")))

    /** `/evidence <record> <link>` adds a clip link; `pick`, `attach` and `skip` are the targets of the chat picker's clicks. */
    class EvidenceCommand : Command("evidence", PunishPerms.CHAT) {
        // Jr. mods attach chat evidence too, so either punish node works.
        override fun hasPermission(
            player: Player,
            permission: String?,
        ): Boolean = PunishPerms.canOpenMenu(player.uuid)

        init {
            setSenderDefaultExecutor { sender, _ -> sender.sendMessage(Component.text("/evidence <record> <link>", NamedTextColor.GOLD)) }
            val record = ArgumentType.Long("record")
            val link = ArgumentType.Word("link")
            addSenderSyntax({ sender, context ->
                val url = context[link]
                if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    sender.sendMessage(Component.text("Evidence must be a link, like a Medal clip.", NamedTextColor.RED))
                    return@addSenderSyntax
                }
                async {
                    val id = context[record]
                    if (Punishments.addEvidence(id, url).isEmpty()) {
                        sender.sendMessage(Component.text("Record #$id doesn't exist.", NamedTextColor.RED))
                    } else {
                        sender.sendMessage(Component.text("Added the link to record #$id.", NamedTextColor.GOLD))
                    }
                }
            }, record, link)

            val index = ArgumentType.Integer("index")
            addSyntax({ staff: Player, context ->
                val pick = picks[staff.uuid] ?: return@addSyntax staff.sendMessage(Component.text("Nothing to attach.", NamedTextColor.RED))
                val chosen = context[index]
                if (chosen !in pick.messages.indices) return@addSyntax
                if (!pick.selected.remove(chosen)) pick.selected += chosen
                staff.sendMessage(
                    Component
                        .text("${pick.selected.size} message(s) selected. ", NamedTextColor.GOLD)
                        .append(actions()),
                )
            }, ArgumentType.Literal("pick"), index)
            addSyntax({ staff: Player, _ ->
                val pick = picks[staff.uuid] ?: return@addSyntax staff.sendMessage(Component.text("Nothing to attach.", NamedTextColor.RED))
                val chosen = pick.selected.toList()
                async {
                    staff.sendMessage(
                        Component.text("Attached ${attach(staff.uuid, pick.offence, chosen)} message(s) as evidence.", NamedTextColor.GOLD),
                    )
                }
            }, ArgumentType.Literal("attach"))
            addSyntax({ staff: Player, _ ->
                picks.remove(staff.uuid)
                staff.sendMessage(Component.text("Skipped chat evidence.", NamedTextColor.GOLD))
            }, ArgumentType.Literal("skip"))
        }
    }

    fun shutdown() {
        picks.clear()
        chat.clear()
    }
}
