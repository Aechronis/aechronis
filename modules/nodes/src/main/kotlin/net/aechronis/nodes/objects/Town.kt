/**
 * Town
 *
 */

package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.constants.ErrorPlayerHasTown
import net.aechronis.nodes.constants.ErrorTerritoryIsTownHome
import net.aechronis.nodes.constants.ErrorTerritoryNotInTown
import net.aechronis.nodes.constants.ErrorTerritoryOwned
import net.aechronis.nodes.constants.ErrorTownExists
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.serdes.SaveState
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.utils.Color
import net.aechronis.nodes.utils.EnumArrayMap
import net.aechronis.nodes.utils.createEnumArrayMap
import net.aechronis.nodes.utils.stringArrayFromSet
import net.aechronis.nodes.utils.stringMapFromMap
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
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
    companion object {
        fun count(): Int = Nodes.towns.size

        fun fromName(name: String): Town? = Nodes.towns[name]

        fun fromPlayer(player: Player): Town? = Resident.fromPlayer(player)?.town

        fun areAllied(town1: Town?, town2: Town?): Boolean {
            if (town1 == null || town2 == null) return false
            if (town1 === town2) return true
            val nation1 = town1.nation
            val nation2 = town2.nation
            return nation1 != null && (nation1 === nation2 || (nation2 != null && nation1.allies.contains(nation2)))
        }

        fun areEnemies(town1: Town?, town2: Town?): Boolean {
            if (town1 == null || town2 == null) return false
            val nation1 = town1.nation
            val nation2 = town2.nation
            return nation1 != null && nation2 != null && nation1.enemies.contains(nation2)
        }

        fun relationshipOfTownToTown(town: Town?, other: Town?): DiplomaticRelationship {
            if (town != null && other != null) {
                if (town === other) return DiplomaticRelationship.TOWN
                val nation = town.nation
                val otherNation = other.nation
                if (nation != null && nation === otherNation) return DiplomaticRelationship.NATION
                if (nation != null && otherNation != null) {
                    if (nation.allies.contains(otherNation)) return DiplomaticRelationship.ALLY
                    if (nation.enemies.contains(otherNation)) return DiplomaticRelationship.ENEMY
                }
            }
            return DiplomaticRelationship.NEUTRAL
        }

        fun relationshipOfPlayerToTown(player: Player, town: Town): DiplomaticRelationship = relationshipOfTownToTown(fromPlayer(player), town)

        fun relationshipOfPlayerToPlayer(player: Player, other: Player): DiplomaticRelationship = relationshipOfTownToTown(fromPlayer(player), fromPlayer(other))

        fun create(name: String, territory: Territory, leader: Resident?): Result<Town> {
            val spawnpoint = leader?.player()?.position ?: Territory.defaultSpawnLocation(territory)
            if (fromName(name) != null) return Result.failure(ErrorTownExists)
            if (territory.town != null) return Result.failure(ErrorTerritoryOwned)
            if (leader?.town != null) return Result.failure(ErrorPlayerHasTown)
            val town = Town(UUID.randomUUID(), name, territory.id, leader, spawnpoint)
            territory.town = town
            if (leader != null) {
                leader.town = town
                leader.needsUpdate()
            }
            Nodes.towns[name] = town
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(town)
        }

        fun load(
            uuid: UUID,
            name: String,
            leader: UUID?,
            homeId: Int,
            spawn: Pos?,
            color: Color?,
            residents: ArrayList<UUID>,
            officers: ArrayList<UUID>,
            territoryIds: ArrayList<Int>,
            capturedTerritoryIds: ArrayList<Int>,
            annexedTerritoryIds: ArrayList<Int>,
            income: MutableMap<Material, Int>,
            permissions: MutableMap<TownPermissions, EnumSet<PermissionsGroup>>,
            protectedBlocks: HashSet<BlockVec>,
            plots: ArrayList<Plot.PlotSaveState> = arrayListOf(),
        ): Town? {
            val leaderResident = leader?.let { Resident.fromUuid(it) }
            val home = Territory.fromId(TerritoryId(homeId))
            if (home == null) {
                System.err.println("Failed to create town $name with home (id = $homeId)")
                return null
            }
            val spawnpoint = spawn ?: Territory.defaultSpawnLocation(home)
            val town = Town(uuid, name, home.id, leaderResident, spawnpoint)
            leaderResident?.town = town
            residents.forEach { id ->
                Resident.fromUuid(id)?.let { resident ->
                    town.residents.add(resident)
                    resident.town = town
                    resident.needsUpdate()
                }
            }
            officers.forEach { id -> Resident.fromUuid(id)?.let { town.officers.add(it) } }
            territoryIds.forEach { id ->
                val territory = Territory.fromId(TerritoryId(id))
                if (territory != null) {
                    town.territories.add(territory.id)
                    territory.town = town
                }
            }
            annexedTerritoryIds.forEach { id ->
                val territoryId = TerritoryId(id)
                if (town.territories.contains(territoryId)) town.annexed.add(territoryId)
            }
            capturedTerritoryIds.forEach { id ->
                val territoryId = TerritoryId(id)
                Territory.fromId(territoryId)?.let { territory ->
                    territory.occupier?.captured?.remove(territoryId)
                    town.captured.add(territoryId)
                    territory.occupier = town
                }
            }
            town.income.storage.putAll(income)
            if (color != null) town.color = color
            if (permissions.values.any { it.isNotEmpty() }) {
                permissions.forEach { (type, groups) ->
                    town.permissions[type].clear()
                    town.permissions[type].addAll(groups)
                }
            } else {
                applyDefaultPermissions(town)
            }
            town.protectedBlocks.addAll(protectedBlocks)
            plots.forEach { state ->
                val plot = Plot(state)
                if (plot.name.isNotBlank() && !town.plots.containsKey(plot.name) && Plot.isValid(town, plot)) town.plots[plot.name] = plot
            }
            Nodes.towns[name] = town
            town.needsUpdate()
            return town
        }

        fun destroy(town: Town) {
            val nation = town.nation
            if (nation != null) {
                if (nation.towns.size == 1) Nation.destroy(nation) else Nation.removeTown(nation, town)
            }
            town.territories.forEach { Territory.fromId(it)?.town = null }
            town.captured.forEach { Territory.fromId(it)?.occupier = null }
            town.residents.forEach { resident ->
                resident.town = null
                resident.nation = null
                resident.needsUpdate()
                resident.player()?.let { player -> nation?.playersOnline?.remove(player) }
            }
            Nodes.towns.remove(town.name)
            Nodes.needsSave = true
            Resident.renderMinimaps()
        }

        fun getPlotAt(town: Town, blockX: Int, blockY: Int, blockZ: Int): Plot? = Plot.at(town, blockX, blockY, blockZ)

        fun unclaim(town: Town, territory: Territory): Result<Territory> {
            if (!town.territories.contains(territory.id)) return Result.failure(ErrorTerritoryNotInTown)
            if (town.home == territory.id) return Result.failure(ErrorTerritoryIsTownHome)
            town.territories.remove(territory.id)
            territory.town = null
            town.annexed.remove(territory.id)
            town.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(territory)
        }

        fun addTerritory(town: Town, territory: Territory): Result<Territory> {
            if (territory.town != null) return Result.failure(ErrorTerritoryOwned)
            town.territories.add(territory.id)
            territory.town = town
            town.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(territory)
        }

        fun capture(town: Town, territory: Territory) {
            val current = territory.occupier
            if (current != null) {
                current.captured.remove(territory.id)
                territory.occupier = null
                current.needsUpdate()
            }
            if (territory.town !== town) {
                town.captured.add(territory.id)
                territory.occupier = town
            }
            town.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
        }

        fun release(territory: Territory) {
            territory.occupier?.let { town ->
                town.captured.remove(territory.id)
                territory.occupier = null
                town.needsUpdate()
                Nodes.needsSave = true
                Resident.renderMinimaps()
            }
        }

        fun addToIncome(town: Town, material: Material, amount: Int) {
            town.income.add(material, amount)
            town.needsUpdate()
            Nodes.needsSave = true
        }

        fun setColor(town: Town, r: Int, g: Int, b: Int) {
            town.color = Color(r, g, b)
            town.needsUpdate()
            Nodes.needsSave = true
        }

        fun setSpawn(town: Town, spawnpoint: Pos): Boolean {
            val territory = Territory.fromBlock(spawnpoint.blockX(), spawnpoint.blockZ())
            if (territory == null || territory.id != town.home) return false
            town.spawnpoint = spawnpoint
            town.needsUpdate()
            Nodes.needsSave = true
            return true
        }

        fun addResident(town: Town, resident: Resident) {
            town.residents.add(resident)
            resident.town = town
            resident.trusted = false
            resident.player()?.let { town.playersOnline.add(it) }
            town.nation?.let { nation ->
                resident.nation = nation
                nation.residents.add(resident)
                resident.player()?.let { nation.playersOnline.add(it) }
            }
            town.needsUpdate()
            resident.needsUpdate()
            Nodes.needsSave = true
        }

        fun removeResident(town: Town, resident: Resident) {
            Resident.stopPlotSelection(resident)
            town.officers.remove(resident)
            town.residents.remove(resident)
            resident.town = null
            val player = resident.player()
            town.nation?.let { nation ->
                resident.nation = null
                nation.residents.remove(resident)
                if (player != null) nation.playersOnline.remove(player)
            }
            if (player != null) town.playersOnline.remove(player)
            town.needsUpdate()
            resident.needsUpdate()
            Nodes.needsSave = true
        }

        fun addOfficer(town: Town, resident: Resident): Boolean {
            if (resident.town !== town) return false
            if (town.officers.contains(resident)) return true
            town.officers.add(resident)
            town.needsUpdate()
            Nodes.needsSave = true
            return true
        }

        fun removeOfficer(town: Town, resident: Resident): Boolean {
            if (resident.town !== town) return false
            town.officers.remove(resident)
            town.needsUpdate()
            Nodes.needsSave = true
            return true
        }

        fun setLeader(town: Town, resident: Resident?) {
            if (resident != null) {
                if (resident.town !== town || town.leader === resident) return
                town.officers.remove(resident)
                town.leader = resident
            } else {
                if (town.leader == null) return
                town.leader = null
            }
            town.needsUpdate()
            Nodes.needsSave = true
        }

        fun rename(town: Town, name: String): Boolean {
            if (Nodes.towns.containsKey(name)) return false
            Nodes.towns.remove(town.name)
            town.name = name
            town.updateNametags()
            Nodes.towns[name] = town
            town.needsUpdate()
            town.nation?.needsUpdate()
            town.residents.forEach { it.needsUpdate() }
            Nodes.needsSave = true
            return true
        }

        fun incomeInventory(town: Town): Inventory {
            if (!town.income.empty()) town.needsUpdate()
            return town.income.getInventory()
        }

        fun setPermissions(town: Town, permissions: Iterable<TownPermissions>, group: PermissionsGroup, flag: Boolean) {
            permissions.forEach { if (flag) town.permissions[it].add(group) else town.permissions[it].remove(group) }
            town.needsUpdate()
            Nodes.needsSave = true
        }

        fun setHome(town: Town, territory: Territory) {
            if (town !== territory.town || town.home == territory.id) return
            town.home = territory.id
            town.spawnpoint = Territory.defaultSpawnLocation(territory)
            Resident.renderMinimaps()
            town.needsUpdate()
            Nodes.needsSave = true
        }

        internal fun protectChest(town: Town, block: BlockVec, protect: Boolean) {
            if (protect) town.protectedBlocks.add(block) else town.protectedBlocks.remove(block)
            town.needsUpdate()
            Nodes.needsSave = true
        }

        internal fun showProtectedChests(town: Town, resident: Resident) {
            val player = resident.player() ?: return
            val particle = Particle.HAPPY_VILLAGER
            val offset = Vec(0.1, 0.1, 0.1)
            var runs = 0
            var task: Task? = null
            task = MinecraftServer.getSchedulerManager().buildTask {
                for (block in town.protectedBlocks) {
                    val locations = listOf(
                        Pos(block.x() + 0.1, block.y() + 0.5, block.z() + 0.1),
                        Pos(block.x() + 0.1, block.y() + 0.5, block.z() + 0.9),
                        Pos(block.x() + 0.9, block.y() + 0.5, block.z() + 0.1),
                        Pos(block.x() + 0.9, block.y() + 0.5, block.z() + 0.9),
                        Pos(block.x() + 0.5, block.y() + 0.5, block.z()),
                        Pos(block.x(), block.y() + 0.5, block.z() + 0.5),
                        Pos(block.x() + 0.5, block.y() + 0.5, block.z() + 1.0),
                        Pos(block.x() + 1.0, block.y() + 0.5, block.z() + 0.5),
                    )
                    player.sendPackets(*locations.map { ParticlePacket(particle, it, offset, 0F, 3) }.toTypedArray())
                }
                runs += 1
                if (runs > 10) task?.cancel()
            }.delay(TaskSchedule.millis(1000)).repeat(TaskSchedule.millis(1000)).schedule()
        }

        internal fun onIncomeInventoryClose() {
            Nodes.needsSave = true
        }

        private fun applyDefaultPermissions(town: Town) {
            enumValues<TownPermissions>().forEach {
                town.permissions[it].clear()
                town.permissions[it].addAll(Nodes.config.defaultTownPermissions[it].orEmpty())
            }
            town.needsUpdate()
        }
    }

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

    // persistent 3D cuboid plots inside the town's claimed territory
    val plots: LinkedHashMap<String, Plot> = linkedMapOf()

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
        val plots: List<Plot.PlotSaveState> = t.plots.values.map { it.getSaveState() }

        override var jsonString: String? = null

        override fun createJsonString(): String {
            val leaderUUID = if (this.leader != null) "\"${this.leader}\"" else null
            val officers = this.officers.joinToString(",", "[", "]") { x -> "\"$x\"" }
            val residents = this.residents.joinToString(",", "[", "]") { x -> "\"$x\"" }
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
                    "\"plots\":[${this.plots.joinToString(",") { it.toJsonString() }}]" +
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
