/**
 * Nation
 * -----------------------------
 *
 */

package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.ErrorNationExists
import net.aechronis.nodes.constants.ErrorPlayerHasNation
import net.aechronis.nodes.constants.ErrorPlayerNotInTown
import net.aechronis.nodes.constants.ErrorTownHasNation
import net.aechronis.nodes.serdes.NationJsonCodec
import net.aechronis.nodes.serdes.SaveState
import net.aechronis.nodes.serdes.snapshotList
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.utils.Color
import net.aechronis.nodes.war.FlagWar
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import java.util.Random
import java.util.UUID

// random number generator
private val random = Random()

class Nation(
    val uuid: UUID,
    name: String,
    var capital: Town, // main town in nation, used for nation leadership
) {

    // Registry keys can change only through the domain rename operation.
    var name: String = name
        private set

    companion object {
        private val nations = linkedMapOf<String, Nation>()

        /** Snapshot of registry membership; the domain objects retain their live identity. */
        internal fun all(): List<Nation> = nations.values.toList()

        /** Called by world reload after runtime users of the old world have stopped. */
        internal fun clearRegistry() {
            nations.clear()
        }

        fun count(): Int = nations.size

        fun fromName(name: String): Nation? = nations[name]

        fun fromUuid(uuid: UUID): Nation? = nations.values.firstOrNull { nation -> nation.uuid == uuid }

        fun areEnemies(nation: Nation, other: Nation): Boolean {
            if (nation === other || other in nation.allies || nation in other.allies) return false
            return FlagWar.isDeathWar || other in nation.enemies || nation in other.enemies
        }

        private fun indexTownMembers(nation: Nation, town: Town) {
            val indexedPlayers = town.playersOnline.associateBy { it.uuid }
            town.residents.forEach { resident ->
                if (resident.town !== town) return@forEach
                resident.nation = nation
                nation.residents.add(resident)
                val player = resident.player() ?: indexedPlayers[resident.uuid]
                if (player != null) {
                    nation.playersOnline.removeAll { it.uuid == resident.uuid }
                    nation.playersOnline.add(player)
                }
                resident.needsUpdate()
            }
        }

        private fun unindexTownMembers(nation: Nation, town: Town) {
            val residents = town.residents.filter { it.town === town }
            val residentIds = residents.mapTo(hashSetOf()) { it.uuid }
            residents.forEach { resident ->
                if (resident.nation === nation) resident.nation = null
                nation.residents.remove(resident)
                resident.needsUpdate()
            }
            nation.playersOnline.removeAll { it.uuid in residentIds }
        }

        fun create(name: String, town: Town, leader: Resident? = null): Result<Nation> {
            if (town.nation != null) return Result.failure(ErrorTownHasNation)
            if (leader?.nation != null) return Result.failure(ErrorPlayerHasNation)
            if (leader != null && !town.residents.contains(leader)) return Result.failure(ErrorPlayerNotInTown)
            if (fromName(name) != null) return Result.failure(ErrorNationExists)

            val nation = Nation(UUID.randomUUID(), name, town)
            Town.initializeCapitalLives(town)
            nations[name] = nation
            nation.towns.add(town)
            town.nation = nation
            indexTownMembers(nation, town)
            town.needsUpdate()
            nation.needsUpdate()
            Nametag.refreshRelationships()
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
            return Result.success(nation)
        }

        fun load(
            uuid: UUID,
            name: String,
            capitalName: String,
            color: Color?,
            towns: ArrayList<String>,
            rallyCap: Int? = null,
        ): Nation {
            val capital = Town.fromName(capitalName) ?: throw net.aechronis.nodes.constants.ErrorTownDoesNotExist
            val nation = Nation(uuid, name, capital)
            Town.initializeCapitalLives(capital)
            if (color != null) nation.color = color
            nation.rallyCap = rallyCap?.takeIf { it > 0 }
            for (townName in towns) {
                val town = Town.fromName(townName) ?: continue
                nation.towns.add(town)
                town.nation = nation
                town.needsUpdate()
                indexTownMembers(nation, town)
            }
            nation.needsUpdate()
            nations[name] = nation
            return nation
        }

        fun destroy(nation: Nation) {
            nation.allies.forEach {
                it.allies.remove(nation)
                it.needsUpdate()
            }
            nation.enemies.forEach {
                it.enemies.remove(nation)
                it.needsUpdate()
            }
            nation.towns.forEach { town ->
                unindexTownMembers(nation, town)
                town.nation = null
                town.needsUpdate()
            }
            nation.towns.clear()
            nation.residents.clear()
            nation.playersOnline.clear()
            nations.remove(nation.name)
            Nametag.refreshRelationships()
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
        }

        fun addTown(nation: Nation, town: Town): Result<Town> {
            if (town.nation != null) return Result.failure(ErrorTownHasNation)
            nation.towns.add(town)
            town.nation = nation
            town.needsUpdate()
            indexTownMembers(nation, town)
            nation.needsUpdate()
            Nametag.refreshRelationships()
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
            return Result.success(town)
        }

        fun removeTown(nation: Nation, town: Town): Result<Town> {
            if (town.nation !== nation) return Result.failure(net.aechronis.nodes.constants.ErrorNationDoesNotHaveTown)
            nation.towns.remove(town)
            unindexTownMembers(nation, town)
            town.nation = null
            if (nation.towns.isEmpty()) {
                destroy(nation)
            } else if (town === nation.capital) {
                nation.capital = nation.towns.first()
                Town.initializeCapitalLives(nation.capital)
                nation.capital.residents.forEach { it.player()?.let { player -> Message.print(player, "Your town is now the capital of ${nation.name}") } }
            }
            town.needsUpdate()
            nation.needsUpdate()
            Nametag.refreshRelationships()
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
            return Result.success(town)
        }

        fun setColor(nation: Nation, r: Int, g: Int, b: Int) {
            nation.color = Color(r, g, b)
            nation.needsUpdate()
            Nodes.markWorldDirty()
        }

        fun rename(nation: Nation, name: String): Boolean {
            if (nations.containsKey(name)) return false
            nations.remove(nation.name)
            nation.name = name
            nations[name] = nation
            nation.needsUpdate()
            nation.towns.forEach { town ->
                town.needsUpdate()
                town.residents.forEach { it.needsUpdate() }
            }
            nation.enemies.forEach { it.needsUpdate() }
            nation.allies.forEach { it.needsUpdate() }
            Nodes.markWorldDirty()
            return true
        }

        fun setRallyCap(nation: Nation, rallyCap: Int) {
            require(rallyCap > 0) { "Rally cap must be at least 1" }
            nation.rallyCap = rallyCap
            nation.needsUpdate()
            Nodes.markWorldDirty()
        }

        fun setCapital(nation: Nation, town: Town) {
            if (town.nation !== nation || nation.capital === town) return
            nation.capital = town
            Town.initializeCapitalLives(town)
            nation.needsUpdate()
            Nodes.markWorldDirty()
        }

        fun addAlly(nation: Nation, other: Nation): Result<Boolean> {
            if ((nation.allies.contains(other) && other.allies.contains(nation)) || nation === other) return Result.failure(net.aechronis.nodes.constants.ErrorAlreadyAllies)
            if (!FlagWar.isDeathWar && (nation.enemies.contains(other) || other.enemies.contains(nation))) {
                return Result.failure(net.aechronis.nodes.constants.ErrorAlreadyEnemies)
            }
            // Accepting a deathwar alliance also ends any declared hostility.
            nation.enemies.remove(other)
            other.enemies.remove(nation)
            nation.allies.add(other)
            other.allies.add(nation)
            nation.towns.forEach { town ->
                town.residents.forEach { it.player()?.let { player -> Message.print(player, "Your nation is now allied with ${other.name}") } }
                town.needsUpdate()
            }
            other.towns.forEach { town ->
                town.residents.forEach { it.player()?.let { player -> Message.print(player, "Your nation is now allied with ${nation.name}") } }
                town.needsUpdate()
            }
            nation.needsUpdate()
            other.needsUpdate()
            FlagWar.revalidateWarAttacks()
            Nametag.refreshRelationships()
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
            return Result.success(true)
        }

        fun removeAlly(nation: Nation, other: Nation): Result<Boolean> {
            if (!nation.allies.contains(other) || !other.allies.contains(nation)) return Result.failure(net.aechronis.nodes.constants.ErrorNotAllies)
            nation.allies.remove(other)
            other.allies.remove(nation)
            nation.towns.forEach { it.needsUpdate() }
            other.towns.forEach { it.needsUpdate() }
            nation.needsUpdate()
            other.needsUpdate()
            Nametag.refreshRelationships()
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
            return Result.success(true)
        }

        fun addEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
            if (nation === enemy) return Result.failure(net.aechronis.nodes.constants.ErrorWarSameNation)
            if (nation.allies.contains(enemy)) return Result.failure(net.aechronis.nodes.constants.ErrorWarAlly)
            if (nation.enemies.contains(enemy) && enemy.enemies.contains(nation)) return Result.failure(net.aechronis.nodes.constants.ErrorAlreadyEnemies)
            nation.enemies.add(enemy)
            enemy.enemies.add(nation)
            nation.towns.forEach { it.needsUpdate() }
            enemy.towns.forEach { it.needsUpdate() }
            nation.needsUpdate()
            enemy.needsUpdate()
            Nametag.refreshRelationships()
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
            return Result.success(true)
        }

        fun removeEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
            nation.enemies.remove(enemy)
            enemy.enemies.remove(nation)
            nation.towns.forEach { it.needsUpdate() }
            enemy.towns.forEach { it.needsUpdate() }
            nation.needsUpdate()
            enemy.needsUpdate()
            Nametag.refreshRelationships()
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
            return Result.success(true)
        }

        fun loadDiplomacy(
            nations: ArrayList<Nation>,
            nationAllies: ArrayList<ArrayList<String>>,
            nationEnemies: ArrayList<ArrayList<String>>,
        ) {
            nations.forEachIndexed { i, nation ->
                nationAllies[i].forEach { name -> fromName(name)?.let { nation.allies.add(it) } }
                nationEnemies[i].forEach { name -> fromName(name)?.let { nation.enemies.add(it) } }
            }
        }
    }

    // must be Set to satisfy bukkit interface in Chat.kt
    val playersOnline: MutableSet<Player> = mutableSetOf()

    val towns: HashSet<Town> = hashSetOf()
    val residents: HashSet<Resident> = hashSetOf()

    // nation's diplomatic relations: allies, enemies
    // determine who nation can attack during war
    val allies: HashSet<Nation> = hashSetOf()
    val enemies: HashSet<Nation> = hashSetOf()

    fun effectiveEnemies(): Set<Nation> = nations.values.filterTo(hashSetOf()) { areEnemies(this, it) }

    // Maximum nation members allowed online while war is enabled. Null means unlimited.
    var rallyCap: Int? = null
        private set

    // color for displaying on map
    // assign random color by default
    var color: Color = Color(
        random.nextInt(256),
        random.nextInt(256),
        random.nextInt(256),
    )

    // json string and memoization flag
    private var saveState = NationSaveState(this)

    private var needsUpdate = false

    // prints out nation object info
    fun printInfo(sender: CommandSender) {
        val leader = this.capital.leader?.name ?: "${ChatColor.GRAY}None"

        // read info out of towns:
        // - get town names
        // - get total residents count
        var residents = 0
        val towns = if (this.towns.isNotEmpty()) {
            val townNames: ArrayList<String> = arrayListOf()
            for (t in this.towns) {
                townNames.add(t.name)
                residents += t.residents.size
            }
            townNames.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }

        val allies = if (this.allies.isNotEmpty()) {
            this.allies.joinToString(", ") { v -> v.name }
        } else {
            "${ChatColor.GRAY}None"
        }

        val currentEnemies = effectiveEnemies()
        val enemies = if (currentEnemies.isNotEmpty()) {
            currentEnemies.joinToString(", ") { v -> v.name }
        } else {
            "${ChatColor.GRAY}None"
        }

        Message.print(sender, "${ChatColor.BOLD}Nation ${this.name}:")
        Message.print(sender, "- Capital${ChatColor.WHITE}: ${this.capital.name}")
        Message.print(sender, "- Leader${ChatColor.WHITE}: $leader")
        Message.print(sender, "- Towns[${this.towns.size}]${ChatColor.WHITE}: $towns")
        Message.print(sender, "- Residents${ChatColor.WHITE}: $residents")
        this.rallyCap?.let { rallyCap ->
            Message.print(sender, "- War rally cap${ChatColor.WHITE}: $rallyCap online players")
        }
        Message.print(sender, "- Allies${ChatColor.WHITE}: $allies")
        Message.print(sender, "- Enemies${ChatColor.WHITE}: $enemies")
    }

    /**
     * Immutable save snapshot, must be composed of immutable primitives.
     * Used to generate json string serialization.
     */
    class NationSaveState(n: Nation) : SaveState() {
        val uuid = n.uuid
        val name = n.name
        val capital = n.capital.name
        val color = n.color
        val towns = n.towns.map { x -> x.name }.snapshotList()
        val allies = n.allies.map { x -> x.name }.snapshotList()
        val enemies = n.enemies.map { x -> x.name }.snapshotList()
        val rallyCap = n.rallyCap

        override fun encode(): String = NationJsonCodec.encode(this)
    }

    // function to let client flag this object as dirty
    fun needsUpdate() {
        this.needsUpdate = true
    }

    // wrapper to return self as savestate
    // - returns memoized copy if needsUpdate false
    // - otherwise, parses self
    fun getSaveState(): NationSaveState {
        if (this.needsUpdate) {
            this.saveState = NationSaveState(this)
            this.needsUpdate = false
        }
        return this.saveState
    }
}
