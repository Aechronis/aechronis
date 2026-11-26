package net.aechronis.tablist

import net.kyori.adventure.text.format.NamedTextColor
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
    ADMIN("admin", "Admin", NamedTextColor.RED, true, 100),
    SENIOR_MOD("senior-mod", "Sr Mod", NamedTextColor.DARK_RED, true, 30),
    MOD("mod-", "Mod", NamedTextColor.DARK_AQUA, true, 20),
    JR_MOD("jr-mod", "Jr Mod", NamedTextColor.AQUA, true, 10),
    LEGEND("legend", "Legend", NamedTextColor.LIGHT_PURPLE, false, 4),
    VIP("vip", "VIP", NamedTextColor.YELLOW, false, 3),
    SUPPORTER_PLUS("supporter-plus", "Supporter+", NamedTextColor.DARK_PURPLE, false, 2),
    SUPPORTER("supporter", "Supporter", NamedTextColor.GRAY, false, 1),
}

class Member(
    val uuid: UUID,
    val name: String,
    val skin: PlayerSkin?,
    val rank: Rank?,
    val weight: Int,
    val vanished: Boolean = false,
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
    val staff: List<Member>,
    val staffWithVanished: List<Member>,
    val totalOnline: Int,
    val nations: List<NationView>,
    private val townByViewer: Map<UUID, TownView>,
    val relation: (NationView, NationView) -> Relation,
) {
    fun townOf(uuid: UUID): TownView? = townByViewer[uuid]
}
