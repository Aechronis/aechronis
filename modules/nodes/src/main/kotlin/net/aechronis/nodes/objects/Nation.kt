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
import net.aechronis.nodes.serdes.SaveState
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.utils.Color
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import java.util.Random
import java.util.UUID

// random number generator
private val random = Random()

class Nation(
    val uuid: UUID,
    var name: String,
    var capital: Town, // main town in nation, used for nation leadership
) {

    companion object {
        fun count(): Int = Nodes.nations.size

        fun fromName(name: String): Nation? = Nodes.nations[name]

        fun create(name: String, town: Town, leader: Resident? = null): Result<Nation> {
            if (town.nation != null) return Result.failure(ErrorTownHasNation)
            if (leader?.nation != null) return Result.failure(ErrorPlayerHasNation)
            if (leader != null && !town.residents.contains(leader)) return Result.failure(ErrorPlayerNotInTown)
            if (fromName(name) != null) return Result.failure(ErrorNationExists)

            val nation = Nation(UUID.randomUUID(), name, town)
            Nodes.nations[name] = nation
            nation.towns.add(town)
            town.nation = nation
            for (resident in town.residents) {
                resident.nation = nation
                resident.needsUpdate()
            }
            town.needsUpdate()
            nation.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(nation)
        }

        fun load(uuid: UUID, name: String, capitalName: String, color: Color?, towns: ArrayList<String>): Nation {
            val capital = Town.fromName(capitalName) ?: throw net.aechronis.nodes.constants.ErrorTownDoesNotExist
            val nation = Nation(uuid, name, capital)
            if (color != null) nation.color = color
            for (townName in towns) {
                val town = Town.fromName(townName) ?: continue
                nation.towns.add(town)
                town.nation = nation
                town.needsUpdate()
                for (resident in town.residents) {
                    resident.nation = nation
                    nation.residents.add(resident)
                    resident.needsUpdate()
                }
            }
            nation.needsUpdate()
            Nodes.nations[name] = nation
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
                town.residents.forEach { resident ->
                    resident.nation = null
                    resident.needsUpdate()
                }
                town.nation = null
                town.needsUpdate()
            }
            Nodes.nations.remove(nation.name)
            Nodes.needsSave = true
            Resident.renderMinimaps()
        }

        fun addTown(nation: Nation, town: Town): Result<Town> {
            if (town.nation != null) return Result.failure(ErrorTownHasNation)
            nation.towns.add(town)
            town.nation = nation
            town.needsUpdate()
            town.residents.forEach { resident ->
                resident.nation = nation
                nation.residents.add(resident)
                resident.player()?.let { nation.playersOnline.add(it) }
                resident.needsUpdate()
            }
            nation.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(town)
        }

        fun removeTown(nation: Nation, town: Town): Result<Town> {
            if (town.nation !== nation) return Result.failure(net.aechronis.nodes.constants.ErrorNationDoesNotHaveTown)
            nation.towns.remove(town)
            town.nation = null
            town.residents.forEach { resident ->
                resident.nation = null
                nation.residents.remove(resident)
                resident.needsUpdate()
            }
            if (nation.towns.isEmpty()) {
                destroy(nation)
            } else if (town === nation.capital) {
                nation.capital = nation.towns.first()
                nation.capital.residents.forEach { it.player()?.let { player -> Message.print(player, "Your town is now the capital of ${nation.name}") } }
            }
            town.needsUpdate()
            nation.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(town)
        }

        fun setColor(nation: Nation, r: Int, g: Int, b: Int) {
            nation.color = Color(r, g, b)
            nation.needsUpdate()
            Nodes.needsSave = true
        }

        fun rename(nation: Nation, name: String): Boolean {
            if (Nodes.nations.containsKey(name)) return false
            Nodes.nations.remove(nation.name)
            nation.name = name
            Nodes.nations[name] = nation
            nation.needsUpdate()
            nation.towns.forEach { town ->
                town.needsUpdate()
                town.residents.forEach { it.needsUpdate() }
            }
            nation.enemies.forEach { it.needsUpdate() }
            nation.allies.forEach { it.needsUpdate() }
            Nodes.needsSave = true
            return true
        }

        fun setCapital(nation: Nation, town: Town) {
            if (town.nation !== nation || nation.capital === town) return
            nation.capital = town
            nation.needsUpdate()
            Nodes.needsSave = true
        }

        fun addAlly(nation: Nation, other: Nation): Result<Boolean> {
            if ((nation.allies.contains(other) && other.allies.contains(nation)) || nation === other) return Result.failure(net.aechronis.nodes.constants.ErrorAlreadyAllies)
            if (nation.enemies.contains(other) || other.enemies.contains(nation)) return Result.failure(net.aechronis.nodes.constants.ErrorAlreadyEnemies)
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
            Nodes.needsSave = true
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
            Nodes.needsSave = true
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
            Nodes.needsSave = true
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
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(true)
        }

        fun loadDiplomacy(
            towns: ArrayList<Town>,
            townAllies: ArrayList<ArrayList<String>>,
            townEnemies: ArrayList<ArrayList<String>>,
            nations: ArrayList<Nation>,
            nationAllies: ArrayList<ArrayList<String>>,
            nationEnemies: ArrayList<ArrayList<String>>,
        ) {
            val allies = hashSetOf<NationPair>()
            val enemies = hashSetOf<NationPair>()
            towns.forEachIndexed { i, town ->
                val nation = town.nation ?: return@forEachIndexed
                if (town !== nation.capital) return@forEachIndexed
                townAllies[i].forEach { name -> Town.fromName(name)?.let { other -> if (other === other.nation?.capital) allies.add(NationPair(nation, other.nation!!)) } }
                townEnemies[i].forEach { name -> Town.fromName(name)?.let { other -> if (other === other.nation?.capital) enemies.add(NationPair(nation, other.nation!!)) } }
            }
            allies.forEach { pair ->
                pair.nation1.allies.add(pair.nation2)
                pair.nation2.allies.add(pair.nation1)
            }
            enemies.forEach { pair ->
                pair.nation1.enemies.add(pair.nation2)
                pair.nation2.enemies.add(pair.nation1)
            }
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

        val enemies = if (this.enemies.isNotEmpty()) {
            this.enemies.joinToString(", ") { v -> v.name }
        } else {
            "${ChatColor.GRAY}None"
        }

        Message.print(sender, "${ChatColor.BOLD}Nation ${this.name}:")
        Message.print(sender, "- Capital${ChatColor.WHITE}: ${this.capital.name}")
        Message.print(sender, "- Leader${ChatColor.WHITE}: $leader")
        Message.print(sender, "- Towns[${this.towns.size}]${ChatColor.WHITE}: $towns")
        Message.print(sender, "- Residents${ChatColor.WHITE}: $residents")
        Message.print(sender, "- Allies${ChatColor.WHITE}: $allies")
        Message.print(sender, "- Enemies${ChatColor.WHITE}: $enemies")
    }

    /**
     * Immutable save snapshot, must be composed of immutable primitives.
     * Used to generate json string serialization.
     */
    class NationSaveState(n: Nation) : SaveState {
        val uuid = n.uuid
        val name = n.name
        val capital = n.capital.name
        val color = n.color
        val towns = n.towns.map { x -> x.name }
        val allies = n.allies.map { x -> x.name }
        val enemies = n.enemies.map { x -> x.name }

        override var jsonString: String? = null

        override fun createJsonString(): String {
            val towns = this.towns.joinToString(",", "[", "]") { x -> "\"$x\"" }
            val allies = this.allies.joinToString(",", "[", "]") { x -> "\"$x\"" }
            val enemies = this.enemies.joinToString(",", "[", "]") { x -> "\"$x\"" }

            val jsonString = (
                "{" +
                    "\"uuid\":\"${this.uuid}\"," +
                    "\"capital\":\"$capital\"," +
                    "\"color\":[${this.color.r},${this.color.g},${this.color.b}]," +
                    "\"towns\":$towns," +
                    "\"allies\":$allies," +
                    "\"enemies\":$enemies" +
                    "}"
                )

            return jsonString
        }
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
