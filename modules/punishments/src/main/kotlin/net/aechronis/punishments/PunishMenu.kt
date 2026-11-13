package net.aechronis.punishments

import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The `/punish <player>` menu: category, then infraction row, then apply or remove a strike. Applying asks in chat for
 * the #evidence link, then picks lengths within the sheet's range and the anonymous, silent, custom message and
 * incomplete options.
 */
internal object PunishMenu {
    private const val PROMPT_TIMEOUT_MS = 120_000L
    private val EVIDENCE_LINK = Regex("""https://(?:(?:canary|ptb)\.)?discord(?:app)?\.com/channels/(\d+)/(\d+)/(\d+)""")

    private class Button(
        val item: ItemStack,
        val click: () -> Unit,
    )

    private class Prompt(
        val question: String,
        /** Returns false to keep waiting, e.g. after an invalid link. */
        val answer: (String) -> Boolean,
        val at: Long = System.currentTimeMillis(),
    )

    private class Session(
        val staff: Player,
        val target: KnownPlayer,
        val strikes: Map<Infraction, Int>,
    ) {
        var inventory: Inventory? = null
        var buttons: Map<Int, Button> = emptyMap()
        var prompt: Prompt? = null
        var infraction: Infraction? = null
        var steps: List<Step> = emptyList()
        var durations: MutableList<Duration?> = mutableListOf()
        var evidence: String? = null
        var reason: String? = null
        var anonymous = false
        var silent = false
        var incomplete = false
    }

    private val sessions = ConcurrentHashMap<UUID, Session>()

    /** Blocking: loads the target's strikes, then opens the menu on the next tick. */
    fun open(
        staff: Player,
        target: KnownPlayer,
    ) {
        val session = Session(staff, target, Punishments.strikes(target.uuid))
        ModuleScheduler.scheduleNextTick {
            if (!staff.isOnline) return@scheduleNextTick
            sessions[staff.uuid] = session
            categories(session)
        }
    }

    private fun categories(session: Session) {
        val allowed = Category.entries.filter { PunishPerms.allowed(session.staff.uuid, it) }
        val slots = listOf(10, 11, 12, 13, 14, 15, 16, 22)
        val buttons =
            allowed.zip(slots).associate { (category, slot) ->
                val lore =
                    Infraction.entries.filter { it.category == category }.map { row ->
                        "${row.severity.label}: ${session.strikes[row] ?: 0}/${row.levels.size} strike(s)"
                    }
                slot to Button(item(icon(category), category.label, lore)) { rows(session, category) }
            }
        show(session, "Punish ${session.target.name}", 3, buttons)
    }

    private fun rows(
        session: Session,
        category: Category,
    ) {
        if (!PunishPerms.allowed(session.staff.uuid, category)) return
        val infractions = Infraction.entries.filter { it.category == category }
        val columns =
            when (infractions.size) {
                1 -> listOf(13)
                2 -> listOf(11, 15)
                else -> listOf(11, 13, 15)
            }
        val buttons = mutableMapOf<Int, Button>()
        infractions.zip(columns).forEach { (row, column) ->
            val strikes = session.strikes[row] ?: 0
            val next = row.level(strikes).joinToString(" + ") { it.describe() }
            buttons[column] =
                Button(
                    item(
                        icon(row.severity),
                        row.label,
                        listOf(row.examples, "", "Strikes: $strikes/${row.levels.size}", "Next: $next") +
                            listOfNotNull("Never decays".takeIf { !row.decays }),
                    ),
                ) {}
            buttons[column + 9] =
                Button(item(Material.LIME_DYE, "Apply strike ${strikes + 1}", listOf(next))) { apply(session, row) }
            buttons[column + 18] =
                Button(
                    item(
                        Material.RED_DYE,
                        "Remove a strike",
                        listOf("Lifts the newest punishment in this row", "and takes off one strike."),
                    ),
                ) {
                    remove(session, row)
                }
        }
        buttons[27] = Button(item(Material.ARROW, "Back", emptyList())) { categories(session) }
        show(session, category.label, 4, buttons)
    }

    private fun apply(
        session: Session,
        row: Infraction,
    ) {
        if (!PunishPerms.allowed(session.staff.uuid, row.category)) return
        session.infraction = row
        session.steps = row.level(session.strikes[row] ?: 0)
        session.durations = session.steps.map { if (it.ranged) middle(it) else it.min }.toMutableList()
        session.incomplete = false
        ask(session, "Paste the #evidence message link for this punishment, or type cancel.") { text ->
            val link = evidenceLink(text)
            if (link == null) {
                session.staff.fail("That isn't a link to a message in the #evidence channel. Paste it again, or type cancel.")
                return@ask false
            }
            session.evidence = link
            // The answer itself is kept out of chat, so confirm it to the staff member only.
            session.staff.info("Evidence: $link")
            if (session.steps.any { it.ranged }) lengths(session) else options(session)
            true
        }
    }

    private fun lengths(session: Session) {
        val buttons = mutableMapOf<Int, Button>()
        session.steps.withIndex().filter { it.value.ranged }.take(2).forEachIndexed { row, (index, step) ->
            val unit = unit(step)
            val value = session.durations[index] ?: step.min!!
            val change = { delta: Duration ->
                session.durations[index] = step.clamp(value + delta)
                lengths(session)
            }
            val range = step.describe()
            buttons[row * 9 + 2] =
                Button(item(Material.RED_STAINED_GLASS_PANE, "-${formatDuration(unit)}", listOf(range))) { change(unit.negated()) }
            buttons[row * 9 + 4] = Button(item(Material.CLOCK, step.action(value).describe(), listOf(range))) {}
            buttons[row * 9 + 6] =
                Button(item(Material.LIME_STAINED_GLASS_PANE, "+${formatDuration(unit)}", listOf(range))) { change(unit) }
        }
        buttons[18] = Button(item(Material.ARROW, "Back", emptyList())) { rows(session, session.infraction!!.category) }
        buttons[22] = Button(item(Material.EMERALD, "Continue", emptyList())) { options(session) }
        show(session, "Length", 3, buttons)
    }

    private fun options(session: Session) {
        val row = session.infraction!!
        val toggle = { slot: Int, name: String, on: Boolean, lore: String, flip: () -> Unit ->
            slot to
                Button(item(if (on) Material.LIME_DYE else Material.GRAY_DYE, "$name: ${if (on) "on" else "off"}", listOf(lore))) {
                    flip()
                    options(session)
                }
        }
        val buttons =
            mutableMapOf(
                toggle(10, "Anonymous", session.anonymous, "Shows \"${Record.ANONYMOUS_STAFF}\" instead of your name to players") {
                    session.anonymous = !session.anonymous
                },
                toggle(12, "Silent", session.silent, "Doesn't announce it in chat") { session.silent = !session.silent },
                14 to
                    Button(
                        item(Material.WRITABLE_BOOK, "Custom message", listOf("What the player sees: ${session.reason ?: row.label}")),
                    ) {
                        ask(session, "Type the message the player sees, or type default to use \"${row.label}\".") { text ->
                            session.reason = text.trim().takeUnless { it.equals("default", true) || it.isEmpty() }?.take(512)
                            options(session)
                            true
                        }
                    },
                18 to
                    Button(item(Material.ARROW, "Back", emptyList())) {
                        if (session.steps.any { it.ranged }) lengths(session) else rows(session, row.category)
                    },
                22 to Button(item(Material.EMERALD_BLOCK, "Confirm", summary(session))) { confirm(session) },
            )
        if (row.incompletable && PunishPerms.any(session.staff.uuid, listOf(PunishPerms.PUNISH))) {
            buttons +=
                toggle(16, "Incomplete", session.incomplete, "Flags it as still needing a nuke or rollback") {
                    session.incomplete = !session.incomplete
                }
        }
        show(session, "Options", 3, buttons)
    }

    private fun summary(session: Session): List<String> =
        listOf(
            "${session.target.name}: ${session.infraction!!.label}",
            actions(session).joinToString(" + ") { it.describe() },
            "Evidence: ${session.evidence}",
        )

    private fun actions(session: Session): List<Action> = session.steps.mapIndexed { index, step -> step.action(session.durations[index]) }

    private fun confirm(session: Session) {
        val row = session.infraction ?: return
        val evidence = session.evidence ?: return
        if (!PunishPerms.allowed(session.staff.uuid, row.category)) return
        end(session)
        val staff = session.staff
        val options = PunishOptions(evidence, session.reason, session.anonymous, session.silent, session.incomplete)
        val actions = actions(session)
        async {
            try {
                val records = Punishments.punish(session.target, row, actions, options, staff.staff(), session.strikes[row] ?: 0)
                if (records.isEmpty()) {
                    staff.fail("${session.target.name}'s ${row.label} strikes changed while the menu was open. Run /punish again.")
                    return@async
                }
                staff.info(
                    "${session.target.name}: ${records.joinToString(
                        " + ",
                    ) { describe(it) }} (${row.label}, strike ${records.first().strike})",
                )
                Evidence.offer(staff, records)
            } catch (error: Exception) {
                staff.fail("Punishment failed: ${error.message}")
                System.err.println("[Punishments] Punish failed: $error")
            }
        }
    }

    private fun describe(record: Record): String = Action(record.type, record.length).describe()

    private fun remove(
        session: Session,
        row: Infraction,
    ) {
        if (!PunishPerms.allowed(session.staff.uuid, row.category)) return
        ask(session, "Type the unban message for ${session.target.name} (${row.label}), or type cancel.") { text ->
            end(session)
            // Permissions can change while the prompt waits.
            if (!PunishPerms.allowed(session.staff.uuid, row.category)) return@ask true
            val staff = session.staff
            async {
                try {
                    val (lifted, strikes) = Punishments.removeStrike(session.target, row, staff.staff(), text.trim().take(512))
                    val what = if (lifted.isEmpty()) "Nothing active to lift" else "Lifted ${lifted.size} record(s)"
                    staff.info("${session.target.name}: $what; ${row.label} is now $strikes/${row.levels.size} strike(s).")
                } catch (error: Exception) {
                    staff.fail("Removing the strike failed: ${error.message}")
                    System.err.println("[Punishments] Remove strike failed: $error")
                }
            }
            true
        }
    }

    /** The link if [text] is a Discord message link in the #evidence channel (any channel when it isn't configured). */
    fun evidenceLink(text: String): String? {
        val match = EVIDENCE_LINK.matchEntire(text.trim()) ?: return null
        val channel = System.getenv("DISCORD_EVIDENCE_CHANNEL_ID")?.trim()?.ifEmpty { null }
        return match.value.takeIf { channel == null || match.groupValues[2] == channel }
    }

    /** The middle of the step's range, on a whole [unit], e.g. 3d for 2-4d and 45m for 30m-1h. */
    fun middle(step: Step): Duration {
        val unit = unit(step)
        val half = (step.max!! - step.min!!).toMillis() / 2.0
        return step.clamp(step.min + unit.multipliedBy(Math.round(half / unit.toMillis())))
    }

    /** Hour steps for ranges under a day (15 minutes when it is at most an hour), day steps otherwise. */
    fun unit(step: Step): Duration =
        when {
            step.max!! <= Duration.ofHours(1) -> Duration.ofMinutes(15)
            step.max - step.min!! < Duration.ofDays(1) -> Duration.ofHours(1)
            else -> Duration.ofDays(1)
        }

    private fun ask(
        session: Session,
        question: String,
        answer: (String) -> Boolean,
    ) {
        session.prompt = Prompt(question, answer)
        session.staff.closeInventory()
        session.staff.info(question)
    }

    /** Takes a staff member's chat line as the answer to their open prompt. Returns whether it was one. */
    fun answer(
        player: Player,
        text: String,
    ): Boolean {
        val session = sessions[player.uuid] ?: return false
        val prompt = session.prompt ?: return false
        if (System.currentTimeMillis() - prompt.at > PROMPT_TIMEOUT_MS) {
            // Still swallow it: an evidence link or unban message must not reach public chat.
            sessions.remove(player.uuid, session)
            player.fail("That /punish prompt expired. Run /punish again.")
            return true
        }
        ModuleScheduler.scheduleNextTick {
            if (sessions[player.uuid] !== session || session.prompt !== prompt) return@scheduleNextTick
            if (text.trim().equals("cancel", true)) {
                end(session)
                player.info("Cancelled.")
            } else if (prompt.answer(text)) {
                if (session.prompt === prompt) session.prompt = null
            }
        }
        return true
    }

    private fun show(
        session: Session,
        title: String,
        rows: Int,
        buttons: Map<Int, Button>,
    ) {
        val inventory = Inventory(InventoryType.valueOf("CHEST_${rows}_ROW"), Component.text(title))
        buttons.forEach { (slot, button) -> inventory.setItemStack(slot, button.item) }
        session.prompt = null
        session.inventory = inventory
        session.buttons = buttons
        if (!session.staff.openInventory(inventory)) end(session)
    }

    private fun end(session: Session) {
        sessions.remove(session.staff.uuid, session)
        session.prompt = null
        if (session.staff.openInventory === session.inventory) session.staff.closeInventory()
    }

    fun onClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uuid] ?: return
        if (event.inventory === session.inventory && session.prompt == null) sessions.remove(event.player.uuid, session)
    }

    fun onClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        if (player.openInventory !== session.inventory) return
        // Cancel every inventory action, including shifts, drags, number keys and drops.
        event.isCancelled = true
        if (event.inventory !== session.inventory) return
        val button = session.buttons[event.slot] ?: return
        val inventory = session.inventory
        ModuleScheduler.scheduleNextTick {
            if (!player.isOnline || sessions[player.uuid] !== session || session.inventory !== inventory) return@scheduleNextTick
            button.click()
        }
    }

    fun forget(uuid: UUID) {
        sessions.remove(uuid)
    }

    fun shutdown() {
        sessions.values.toList().forEach { session ->
            val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(session.staff.uuid)
            if (player != null && player.openInventory === session.inventory) player.closeInventory()
        }
        sessions.clear()
    }

    private fun icon(category: Category): Material =
        when (category) {
            Category.CHAT -> Material.PAPER
            Category.BUILD -> Material.BRICKS
            Category.GENERAL -> Material.BOOK
            Category.WAR -> Material.IRON_SWORD
            Category.NATION -> Material.WHITE_BANNER
            Category.MODIFICATIONS -> Material.REDSTONE
            Category.BAN_EVASION -> Material.ENDER_PEARL
            Category.ADMIN -> Material.BARRIER
        }

    private fun icon(severity: Severity): Material =
        when (severity) {
            Severity.MINOR -> Material.YELLOW_CONCRETE
            Severity.MEDIUM -> Material.ORANGE_CONCRETE
            Severity.MAJOR -> Material.RED_CONCRETE
            Severity.BLACKLIST -> Material.BLACK_CONCRETE
        }

    private fun item(
        material: Material,
        name: String,
        lore: List<String>,
    ): ItemStack =
        ItemStack
            .of(material)
            .withCustomName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            .withLore(lore.map { Component.text(it, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false) })
}
