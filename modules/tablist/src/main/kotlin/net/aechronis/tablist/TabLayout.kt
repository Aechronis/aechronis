package net.aechronis.tablist

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.entity.PlayerSkin
import java.util.UUID

/** A tab row. [owner] null means a text-only row (transparent head). */
data class Cell(
    val owner: UUID?,
    val skin: PlayerSkin?,
    val text: Component,
)

const val ROWS = 16
const val COLUMNS = 4
private const val MAX_OTHER_TOWNS = 4
private val BLANK = Cell(null, null, Component.empty())
private val STAR = Component.text("★ ", NamedTextColor.YELLOW)
private val CROWN = Component.text("♛ ", NamedTextColor.GOLD)
private val BRIGHT = NamedTextColor.GREEN
private val DARK = NamedTextColor.DARK_GREEN

/** Builds the 80 cells for a viewer. Column pieces are cached per frame so cost scales with towns/nations, not viewers. */
class TabLayout(
    private val world: World,
) {
    private val col1 = HashMap<Int, List<Cell>>()
    private val townColumns = HashMap<TownView, List<Cell>>()
    private val relationColumns = HashMap<NationView, Pair<List<Cell>, List<Cell>>>()

    fun build(
        viewerId: UUID,
        viewerLevel: Int,
    ): List<Cell>? {
        val town = world.townOf(viewerId) ?: return null
        val out = ArrayList<Cell>(ROWS * COLUMNS)
        out += staffColumn(viewerLevel)
        out += townColumns.getOrPut(town) { townColumn(town) }
        val nation = town.nation
        val (allies, others) = relationColumns.getOrPut(nation ?: NO_NATION) { relationColumns(nation) }
        out += allies
        out += others
        return out
    }

    /** Players without a town only see column 1; their other columns are padded blank. */
    fun buildStaffOnly(viewerLevel: Int): List<Cell> = staffColumn(viewerLevel) + List(ROWS * (COLUMNS - 1)) { BLANK }

    private fun staffColumn(viewerLevel: Int): List<Cell> =
        col1.getOrPut(viewerLevel) {
            // Same rule as the Vanish visibility rule: a viewer sees a vanished player only with a strictly higher level.
            val list = world.staffWithVanished.filter { it.vanishLevel == 0 || viewerLevel > it.vanishLevel }
            val rows = ArrayList<Cell>()
            rows += textRow("Staff", NamedTextColor.GOLD, bold = true)
            rows += divider()
            val room = ROWS - 4
            list.take(if (list.size > room) room - 1 else room).forEach { m ->
                var text = playerText(m, NamedTextColor.WHITE)
                if (m.vanishLevel > 0) text = text.append(Component.text(" (V)", NamedTextColor.GRAY))
                rows += Cell(m.uuid, m.skin, text)
            }
            if (list.size > room) rows += textRow("+${list.size - room + 1} more", NamedTextColor.GRAY)
            pad(rows, ROWS - 2)
            rows += divider()
            rows += textRow("Online: ${world.totalOnline}", NamedTextColor.GOLD)
            rows
        }

    private fun townColumn(town: TownView): List<Cell> {
        val nation = town.nation
        val others =
            nation
                ?.towns
                ?.filter { it !== town && it.online.isNotEmpty() }
                ?.sortedByDescending { it.online.size }
                .orEmpty()
        val hidden = others.drop(MAX_OTHER_TOWNS - 1)
        val townRows: List<Cell> =
            if (others.size > MAX_OTHER_TOWNS) {
                others.take(MAX_OTHER_TOWNS - 1).map { townLine(it, DARK) } +
                    textRow(
                        "+${hidden.size} more: ${hidden.sumOf { it.online.size }}",
                        DARK,
                    )
            } else {
                others.map { townLine(it, DARK) }
            }
        val shownOthers: List<Cell> = if (others.isEmpty()) emptyList() else listOf(divider(DARK)) + townRows
        val top = ArrayList<Cell>()
        if (nation != null) top += textRow("Nation: ${nation.onlineTotal}", DARK, bold = true)
        top += textRow("${town.name}: ${town.online.size}", BRIGHT, bold = true)
        top += divider(BRIGHT)

        val memberRoom = ROWS - top.size - shownOthers.size
        val members =
            town.online.sortedWith(
                compareBy<Member>({ memberOrder(town, it) }, { -it.weight }, { it.name.lowercase() }),
            )
        val shown = if (members.size > memberRoom) memberRoom - 1 else members.size
        members.take(shown).forEach { m ->
            val mark =
                when {
                    m.uuid == town.leader -> CROWN
                    m.uuid in town.officers -> STAR
                    else -> Component.text("· ", NamedTextColor.DARK_GRAY)
                }
            top += Cell(m.uuid, m.skin, mark.append(playerText(m, BRIGHT)))
        }
        if (members.size > shown) top += textRow("+${members.size - shown} more", BRIGHT)
        pad(top, ROWS - shownOthers.size)
        return top + shownOthers
    }

    private fun memberOrder(
        town: TownView,
        m: Member,
    ) = when {
        m.uuid == town.leader -> 0
        m.uuid in town.officers -> 1
        else -> 2
    }

    private fun relationColumns(viewerNation: NationView?): Pair<List<Cell>, List<Cell>> {
        val allies = ArrayList<NationView>()
        val enemies = ArrayList<NationView>()
        val neutral = ArrayList<NationView>()
        for (n in world.nations) {
            if (n === viewerNation || n.onlineTotal == 0) continue
            when (viewerNation?.let { world.relation(it, n) } ?: Relation.NEUTRAL) {
                Relation.ALLY -> allies += n
                Relation.ENEMY -> enemies += n
                Relation.NEUTRAL -> neutral += n
            }
        }
        val a = fit(section("Allies", NamedTextColor.BLUE, allies), ROWS)
        val e = section("Enemies", NamedTextColor.RED, enemies)
        val n = section("Neutral", NamedTextColor.GOLD, neutral)
        val enemyBudget = if (e.size + n.size <= ROWS) e.size else minOf(e.size, maxOf(ROWS / 2, ROWS - n.size))
        val neutralBudget = minOf(n.size, ROWS - enemyBudget)
        val right = fit(e, enemyBudget) + fit(n, neutralBudget)
        return pad(a.toMutableList(), ROWS) to pad(right.toMutableList(), ROWS)
    }

    private fun section(
        title: String,
        color: TextColor,
        nations: List<NationView>,
    ): List<Cell> {
        val rows = ArrayList<Cell>()
        rows += textRow("$title: ${nations.sumOf { it.onlineTotal }}", color, bold = true)
        rows += divider(color)
        nations.sortedByDescending { it.onlineTotal }.forEach { n ->
            rows += textRow("${n.name}: ${n.onlineTotal}", color)
        }
        return rows
    }

    /** Truncates to [budget] rows, replacing the last row with a "+k more" marker. */
    private fun fit(
        rows: List<Cell>,
        budget: Int,
    ): List<Cell> {
        if (rows.size <= budget) return rows
        if (budget <= 0) return emptyList()
        val color = rows.first().text.color() ?: NamedTextColor.GRAY
        return rows.take(budget - 1) + textRow("+${rows.size - budget + 1} more", color)
    }

    private fun townLine(
        t: TownView,
        color: TextColor,
    ) = textRow("${t.name}: ${t.online.size}", color)

    private fun pad(
        rows: MutableList<Cell>,
        size: Int,
    ): MutableList<Cell> {
        while (rows.size < size) rows += BLANK
        return rows
    }

    private fun playerText(
        m: Member,
        nameColor: TextColor,
    ): Component {
        val name = Component.text(m.name, nameColor)
        val rank = m.rank ?: return name
        return Component.text("[${rank.label}] ", rank.color).append(name)
    }

    private fun textRow(
        text: String,
        color: TextColor,
        bold: Boolean = false,
    ) = Cell(null, null, Component.text(text, color).decoration(TextDecoration.BOLD, bold))

    private companion object {
        val NO_NATION = NationView("", -1)
    }
}

private fun divider(color: TextColor = NamedTextColor.DARK_GRAY) = Cell(null, null, Component.text("─────────", color))
