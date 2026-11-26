package net.aechronis.tablist

import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.staff.managers.Vanish
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import java.util.IdentityHashMap
import java.util.Random
import java.util.UUID

object TabWorlds {
    private val staffOrder = compareBy<Member>({ -it.weight }, { it.name.lowercase() })

    /** True if a player with no skin would get the default skin the pack turns transparent (see TabSender.BLANK_SKIN_INDEX). */
    fun hasTransparentDefaultSkin(uuid: UUID): Boolean = Math.floorMod(uuid.hashCode(), 18) == TabSender.BLANK_SKIN_INDEX

    private fun rankWeights(lp: LuckPerms): List<Pair<Rank, Int>> =
        Rank.entries
            .map {
                it to (
                    lp.groupManager
                        .getGroup(it.group)
                        ?.weight
                        ?.orElse(it.fallbackWeight) ?: it.fallbackWeight
                )
            }.sortedByDescending { it.second }

    private fun highest(
        lp: LuckPerms,
        uuid: UUID,
        ranks: List<Pair<Rank, Int>>,
    ): Pair<Rank, Int>? {
        // Real group membership, not checkPermission("group.x"): that also matches wildcards, so a `*` holder looked like every rank.
        val user = lp.userManager.getUser(uuid) ?: return null
        val groups = user.getInheritedGroups(lp.contextManager.staticQueryOptions).mapTo(HashSet()) { it.name }
        return ranks.firstOrNull { it.first.group in groups }
    }

    /** Chat prefix for the highest rank (purchased or staff), or empty. */
    fun chatPrefix(uuid: UUID): Component {
        val lp = LuckPermsProvider.get()
        return prefix(highest(lp, uuid, rankWeights(lp))?.first)
    }

    /** One color per character of [text]. */
    private fun letters(
        text: String,
        vararg colors: Int,
    ) = text.mapIndexed { i, c -> c.toString() to colors[i] }

    /** Bold label segments (text, color); a segment may span several characters sharing one color. */
    private val chatGradients: Map<Rank, List<Pair<String, Int>>> =
        mapOf(
            Rank.SUPPORTER to
                letters("SUPPORTER", 0x56B4E9, 0x49BFE0, 0x3DCBD7, 0x30D6CE, 0x24C2D7, 0x18AEDF, 0x0B9AE7, 0x0086EF, 0x0086EF),
            Rank.SUPPORTER_PLUS to
                letters("SUPPORTER+", 0xB16CFF, 0xBB60F5, 0xC654EB, 0xD148E1, 0xDC3CD7, 0xE730CD, 0xF224C3, 0xFD18B9, 0xE925CB, 0xD532DD),
            Rank.VIP to letters("VIP", 0xFFB52E, 0xFFC247, 0xFFD660),
            Rank.LEGEND to letters("LEGEND", 0xFFE66D, 0xFFDF4D, 0xFFD82E, 0xFFD00F, 0xFFC900, 0xFFE66D),
            Rank.JR_MOD to
                listOf("J" to 0x6EDD56, "r" to 0x59D944, " M" to 0x43D532, "O" to 0x2DD120, "D" to 0x17CD0E),
            Rank.MOD to letters("MOD", 0x36D6C4, 0x26CFCF, 0x16C8DA),
            Rank.SENIOR_MOD to
                listOf("S" to 0x459BFF, "r" to 0x3589F5, "-M" to 0x2577EB, "O" to 0x1565E1, "D" to 0x0553D7),
            Rank.ADMIN to letters("ADMIN", 0xD09AFF, 0xB873F0, 0xA34CE1, 0x8E25D2, 0x7900C3),
        )

    /** Bold gradient label followed by a space (e.g. `VIP `). */
    fun prefix(rank: Rank?): Component {
        val parts = rank?.let { chatGradients[it] } ?: return Component.empty()
        return Component
            .text()
            .append(parts.map { (t, c) -> Component.text(t, Style.style(TextColor.color(c), TextDecoration.BOLD)) })
            .append(Component.text(" "))
            .build()
    }

    /** Real world from Nodes + LuckPerms. Vanished players are excluded from every count. */
    fun real(): World {
        val lp = LuckPermsProvider.get()
        val ranks = rankWeights(lp)

        fun member(p: Player): Member {
            val hit = highest(lp, p.uuid, ranks)
            return Member(p.uuid, p.username, p.skin, hit?.first, hit?.second ?: 0, Vanish.vanishLevel(p))
        }

        val players = MinecraftServer.getConnectionManager().onlinePlayers.toList()
        val members = players.map(::member)
        val townViews = IdentityHashMap<Town, TownView>()
        val nationViews = IdentityHashMap<Nation, NationView>()
        val byViewer = HashMap<UUID, TownView>()
        players.forEachIndexed { i, p ->
            val town = Resident.fromPlayer(p)?.town ?: return@forEachIndexed
            val view =
                townViews.getOrPut(town) {
                    TownView(town.name, town.leader?.uuid, town.officers.mapTo(HashSet()) { it.uuid }).also { tv ->
                        town.nation?.let { n ->
                            val nv = nationViews.getOrPut(n) { NationView(n.name, nationViews.size) }
                            tv.nation = nv
                            nv.towns += tv
                        }
                    }
                }
            byViewer[p.uuid] = view
            if (members[i].vanishLevel == 0) view.online += members[i]
        }
        val nationByView = nationViews.entries.associate { (n, v) -> v to n }
        val visible = members.filter { it.vanishLevel == 0 }
        return World(
            staffWithVanished = members.filter { it.rank?.staff == true }.sortedWith(staffOrder),
            totalOnline = visible.size,
            nations = nationViews.values.toList(),
            townByViewer = byViewer,
            relation = { a, b ->
                val na = nationByView.getValue(a)
                val nb = nationByView.getValue(b)
                when {
                    nb in na.allies -> Relation.ALLY
                    Nation.areEnemies(na, nb) -> Relation.ENEMY
                    else -> Relation.NEUTRAL
                }
            },
        )
    }

    /** Dev only: [count] fake players over a synthetic world; every real player becomes a member of its first town. */
    fun spoof(count: Int): World {
        val real = real()
        val rnd = Random(42)
        val nationCount = (count / 40).coerceIn(4, 14)
        val nations = (0 until nationCount).map { NationView("Nation${it + 1}", it) }
        val towns = ArrayList<TownView>()
        nations.forEach { n ->
            repeat(3 + rnd.nextInt(4)) { t ->
                val town = TownView("${n.name}Town${t + 1}", UUID(rnd.nextLong(), rnd.nextLong()), HashSet())
                town.nation = n
                n.towns += town
                towns += town
            }
        }
        val weights = DoubleArray(towns.size) { 1.0 + rnd.nextInt(10) }
        val sum = weights.sum()
        var made = 0
        towns.forEachIndexed { ti, town ->
            val share = if (ti == towns.lastIndex) count - made else (count * weights[ti] / sum).toInt()
            repeat(share.coerceAtLeast(0)) { k ->
                val id = if (k == 0) town.leader!! else UUID(rnd.nextLong(), rnd.nextLong())
                if (k in 1..3) town.officers.add(id)
                val rank = if (rnd.nextInt(8) == 0) Rank.entries[4 + rnd.nextInt(4)] else null
                town.online += Member(id, "Spoof${made + k}", null, rank, rank?.fallbackWeight ?: 0)
            }
            made += share
        }
        val first = towns.first()
        val viewers = HashMap<UUID, TownView>()
        MinecraftServer.getConnectionManager().onlinePlayers.forEach { viewers[it.uuid] = first }
        val fakeStaff =
            Rank.entries.take(4).mapIndexed { i, r -> Member(UUID(i.toLong(), 1), "SpoofStaff$i", null, r, r.fallbackWeight) }
        val staff = (real.staffWithVanished + fakeStaff).sortedWith(staffOrder)
        return World(
            staffWithVanished = staff,
            totalOnline = count + real.totalOnline,
            nations = nations,
            townByViewer = viewers,
            relation = { a, b ->
                when ((a.index + b.index) % 4) {
                    1 -> Relation.ALLY
                    2 -> Relation.ENEMY
                    else -> Relation.NEUTRAL
                }
            },
        )
    }
}
