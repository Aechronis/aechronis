package net.aechronis.tablist

import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.staff.managers.Vanish
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import java.util.IdentityHashMap
import java.util.Random
import java.util.UUID

object TabWorlds {
    private val staffOrder = compareBy<Member>({ -it.weight }, { it.name.lowercase() })

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
        val perms =
            lp.userManager
                .getUser(uuid)
                ?.cachedData
                ?.permissionData ?: return null
        return ranks.firstOrNull { perms.checkPermission("group.${it.first.group}").asBoolean() }
    }

    /** Chat prefix like "[VIP] " for the highest purchased rank, or "" (staff ranks are not shown in chat). */
    fun chatPrefix(uuid: UUID): String {
        val lp = LuckPermsProvider.get()
        return prefix(highest(lp, uuid, rankWeights(lp).filter { !it.first.staff })?.first)
    }

    fun prefix(rank: Rank?): String {
        if (rank == null || rank.staff) return ""
        return LegacyComponentSerializer.legacySection().serialize(Component.text("[${rank.label}]", rank.color)) + "§r "
    }

    /** Real world from Nodes + LuckPerms. Vanished players are excluded from every count. */
    fun real(): World {
        val lp = LuckPermsProvider.get()
        val ranks = rankWeights(lp)

        fun member(p: Player): Member {
            val hit = highest(lp, p.uuid, ranks)
            return Member(p.uuid, p.username, p.skin, hit?.first, hit?.second ?: 0, Vanish.isVanished(p))
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
            if (!members[i].vanished) view.online += members[i]
        }
        val nationByView = nationViews.entries.associate { (n, v) -> v to n }
        val visible = members.filter { !it.vanished }
        return World(
            staff = visible.filter { it.rank?.staff == true }.sortedWith(staffOrder),
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
        val staff = (real.staff + fakeStaff).sortedWith(staffOrder)
        return World(
            staff = staff,
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
