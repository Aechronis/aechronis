/**
 * Town
 *
 */

package luna.nodes.nodes.objects

import luna.nodes.nodes.Message
import luna.nodes.nodes.constants.PermissionsGroup
import luna.nodes.nodes.constants.TownPermissions
import luna.nodes.nodes.serdes.SaveState
import luna.nodes.nodes.utils.ChatColor
import luna.nodes.nodes.utils.Color
import luna.nodes.nodes.utils.EnumArrayMap
import luna.nodes.nodes.utils.createEnumArrayMap
import luna.nodes.nodes.utils.stringArrayFromSet
import luna.nodes.nodes.utils.stringMapFromMap
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.item.Material
import net.minestom.server.timer.Task
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

// internal town id counter
private var townNametagIdCounter: Int = 0

class Town(
    val uuid: UUID,
    var name: String,
    var home: TerritoryId, // main territory owned by town
    var leader: Resident?,
    var spawnpoint: Pos,
) {
    // town numeric id, not saved, can change on reload
    // used by nametag scoreboard system (cannot use name because 16 char team limit)
    val townNametagId: Int = townNametagIdCounter++

    // residents belong to town
    val residents: HashSet<Resident> = hashSetOf()

    // officer rank players (assistants to leader)
    val officers: HashSet<Resident> = hashSetOf()

    // territories owned by town
    // this includes annexed territories
    val territories: HashSet<TerritoryId> = hashSetOf(home)

    // separate set of all annexed territories
    val annexed: HashSet<TerritoryId> = hashSetOf()

    // territories captured by town (but not annexed)
    val captured: HashSet<TerritoryId> = hashSetOf()

    // nation for town
    var nation: Nation? = null

    // players currently online in town
    // must be Set to satisfy bukkit interface in Chat.kt
    val playersOnline: MutableSet<Player> = mutableSetOf()

    // income storage container from territory income
    // map material -> current amount of it
    val income: IncomeInventory = IncomeInventory()

    // permission flags, map of
    // town permissions category -> set of allowed groups in (town, ally, nation, outsider)
    val permissions: EnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>> =
        createEnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>> { _ -> EnumSet.of(PermissionsGroup.TOWN) }

    // protected chest blocks in town (for leader, officers, + trusted players)
    val protectedBlocks: HashSet<BlockVec> = hashSetOf()

    // color for displaying on map
    var color: Color = Color(
        ThreadLocalRandom.current().nextInt(256),
        ThreadLocalRandom.current().nextInt(256),
        ThreadLocalRandom.current().nextInt(256),
    )

    // re-usable nametag strings, for each diplomatic relation type
    var nametagTown: String = "${ChatColor.GREEN}[${this.name}]"
    var nametagNation: String = "${ChatColor.DARK_GREEN}[${this.name}]"
    var nametagNeutral: String = "${ChatColor.GOLD}[${this.name}]"
    var nametagAlly: String = "${ChatColor.DARK_AQUA}[${this.name}]"
    var nametagEnemy: String = "${ChatColor.RED}[${this.name}]"

    // players applying to town and their tasks
    val applications: HashMap<Resident, Task> = hashMapOf()

    // json string and memoization flag
    private var saveState: TownSaveState

    private var needsUpdate = false

    init {
        if (leader != null) {
            // add creator to residents list
            this.residents.add(leader!!)

            // add creator as online
            if (this.leader!!.player()?.isOnline == true) {
                this.playersOnline.add(leader!!.player()!!)
            }
        }

        // generate initial json string (must be at end to capture state after leader added)
        this.saveState = TownSaveState(this)
    }

    override fun hashCode(): Int = this.uuid.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Town) return false
        return this.uuid == other.uuid
    }

    // update town nametag display strings from name
    // (different color for each diplomacy group)
    fun updateNametags() {
        this.nametagTown = "${ChatColor.GREEN}[${this.name}]"
        this.nametagNation = "${ChatColor.DARK_GREEN}[${this.name}]"
        this.nametagNeutral = "${ChatColor.GOLD}[${this.name}]"
        this.nametagAlly = "${ChatColor.DARK_AQUA}[${this.name}]"
        this.nametagEnemy = "${ChatColor.RED}[${this.name}]"
    }

    // prints out nation object info
    fun printInfo(sender: CommandSender) {
        val nation = this.nation?.name ?: "${ChatColor.GRAY}None"
        val leader = this.leader?.name ?: "${ChatColor.GRAY}None"
        val officers = if (this.officers.isNotEmpty()) {
            this.officers.joinToString(", ") { r -> r.name }
        } else {
            "${ChatColor.GRAY}None"
        }
        val residents = if (this.residents.isNotEmpty()) {
            this.residents.joinToString(", ") { r -> r.name }
        } else {
            "${ChatColor.GRAY}None"
        }
        // allies/enemies are inherited from nation
        val allies = if (this.nation?.allies?.isNotEmpty() == true) {
            this.nation!!.allies.joinToString(", ") { it -> it.name }
        } else {
            "${ChatColor.GRAY}None"
        }
        val enemies = if (this.nation?.enemies?.isNotEmpty() == true) {
            this.nation!!.enemies.joinToString(", ") { it -> it.name }
        } else {
            "${ChatColor.GRAY}None"
        }

        Message.print(sender, "${ChatColor.BOLD}Town ${this.name}:")
        Message.print(sender, "- Home${ChatColor.WHITE}: Territory (id = ${this.home})")
        Message.print(sender, "- Territories${ChatColor.WHITE}: ${this.territories.size}")
        Message.print(sender, "- Nation${ChatColor.WHITE}: $nation")
        Message.print(sender, "- Allies${ChatColor.WHITE}: $allies")
        Message.print(sender, "- Enemies${ChatColor.WHITE}: $enemies")
        Message.print(sender, "- Leader${ChatColor.WHITE}: $leader")
        Message.print(sender, "- Officers[${this.officers.size}]${ChatColor.WHITE}: $officers")
        Message.print(sender, "- Residents[${this.residents.size}]${ChatColor.WHITE}: $residents")
    }

    /**
     * Immutable save snapshot, must be composed of immutable primitives.
     * Used to generate json string serialization.
     */
    class TownSaveState(t: Town) : SaveState {
        val uuid = t.uuid
        val name = t.name
        val leader = t.leader?.uuid
        val home = t.home
        val spawnpoint = doubleArrayOf(t.spawnpoint.x, t.spawnpoint.y, t.spawnpoint.z)
        val color = intArrayOf(t.color.r, t.color.g, t.color.b)
        val permissions = t.permissions.copyOf()
        val residents = t.residents.map { x -> x.uuid }
        val officers = t.officers.map { x -> x.uuid }
        val territories = t.territories.toList()
        val annexed = t.annexed.toList()
        val captured = t.captured.toList()
        val income = t.income.storage.toMutableMap()
        val protectedBlocks: HashSet<BlockVec> = HashSet(t.protectedBlocks)

        override var jsonString: String? = null

        override fun createJsonString(): String {
            val leaderUUID = if (this.leader != null) "\"${this.leader}\"" else null
            val officers = this.officers.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val residents = this.residents.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val territories = this.territories.joinToString(",", "[", "]")
            val annexed = this.annexed.joinToString(",", "[", "]")
            val captured = this.captured.joinToString(",", "[", "]")
            val income = stringMapFromMap<Material, Int>(
                this.income,
                { k -> "\"$k\"" },
                { v -> "$v" },
            )

            val col = this.color
            val spawn = "[${this.spawnpoint[0]},${this.spawnpoint[1]},${this.spawnpoint[2]}]"

            val permissions = permissionsToJsonString(this.permissions)

            val jsonStrong = (
                "{" +
                    "\"uuid\":\"${this.uuid}\"," +
                    "\"leader\":$leaderUUID," +
                    "\"home\":${this.home}," +
                    "\"spawn\":$spawn," +
                    "\"color\":[${col[0]},${col[1]},${col[2]}]," +
                    "\"perms\":$permissions," +
                    "\"residents\":$residents," +
                    "\"officers\":$officers," +
                    "\"territories\":$territories," +
                    "\"annexed\":$annexed," +
                    "\"captured\":$captured," +
                    "\"income\":$income," +
                    "\"protect\":${blocksToJsonString(this.protectedBlocks)}," +
                    "}"
                )

            return jsonStrong
        }
    }

    // function to let client flag this object as dirty
    fun needsUpdate() {
        this.needsUpdate = true
    }

    // wrapper to return self as savestate
    // - returns memoized copy if needsUpdate false
    // - otherwise, parses self
    fun getSaveState(): TownSaveState {
        if (this.needsUpdate) {
            this.saveState = TownSaveState(this)
            this.needsUpdate = false
        }
        return this.saveState
    }
}

// string format for town permissions
private fun permissionsToJsonString(permissions: EnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>>): String {
    val str = StringBuilder()

    str.append("{")

    var index = 0
    for (type in enumValues<TownPermissions>()) {
        val groups = permissions[type]
        str.append("\"${type}\":")
        str.append(stringArrayFromSet<PermissionsGroup>(groups) { g -> "${g.ordinal}" })
        if (index < permissions.size - 1) {
            str.append(",")
        }
        index += 1
    }

    str.append("}")

    val s = str.toString()
    return s
}

// string format for protected blocks HashSet<BlockVec>
private fun blocksToJsonString(blocks: HashSet<BlockVec>): String {
    val str = StringBuilder()
    str.append("[")

    var index = 0
    for (block in blocks) {
        str.append("[${block.blockX},${block.blockY},${block.blockZ}]")
        if (index < blocks.size - 1) {
            str.append(",")
        }
        index += 1
    }

    str.append("]")

    val s = str.toString()
    return s
}
