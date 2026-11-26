package net.aechronis.tablist

import net.kyori.adventure.text.format.TextColor
import net.minestom.server.entity.PlayerSkin
import java.util.UUID

enum class Rank(
    val group: String,
    val label: String,
    val color: TextColor,
    val staff: Boolean,
    val fallbackWeight: Int,
) {
    ADMIN("admin", "Admin", TextColor.color(0x2A3FD0), true, 100),
    SENIOR_MOD("senior-mod", "Sr Mod", TextColor.color(0x3F66E8), true, 30),
    MOD("mod-", "Mod", TextColor.color(0x5E94F5), true, 20),
    JR_MOD("jr-mod", "Jr Mod", TextColor.color(0x8FCBFF), true, 10),
    LEGEND("legend", "Legend", TextColor.color(0xFF69B4), false, 4),
    VIP("vip", "VIP", TextColor.color(0xE15FD0), false, 3),
    SUPPORTER_PLUS("supporter-plus", "Supporter+", TextColor.color(0xB85FE6), false, 2),
    SUPPORTER("supporter", "Supporter", TextColor.color(0x8A4FFF), false, 1),
}

class Member(
    val uuid: UUID,
    val name: String,
    val skin: PlayerSkin?,
    val rank: Rank?,
    val weight: Int,
    val vanishLevel: Int = 0,
)

class TownView(
    val name: String,
    val leader: UUID?,
    val officers: MutableSet<UUID>,
) {
    var nation: NationView? = null
    val online = ArrayList<Member>()
}

class NationView(
    val name: String,
    val index: Int,
) {
    val towns = ArrayList<TownView>()
    val onlineTotal: Int get() = towns.sumOf { it.online.size }
}

enum class Relation { ALLY, ENEMY, NEUTRAL }

/** One immutable-by-convention snapshot of who is online, built once per refresh. */
class World(
    val staffWithVanished: List<Member>,
    val totalOnline: Int,
    val nations: List<NationView>,
    private val townByViewer: Map<UUID, TownView>,
    val relation: (NationView, NationView) -> Relation,
) {
    fun townOf(uuid: UUID): TownView? = townByViewer[uuid]
}
