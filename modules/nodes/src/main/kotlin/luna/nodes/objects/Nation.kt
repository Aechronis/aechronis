/**
 * Nation
 * -----------------------------
 *
 */

package luna.nodes.objects

import luna.nodes.utils.ChatColor
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import luna.nodes.Message
import luna.nodes.serdes.SaveState
import luna.nodes.utils.Color
import java.util.Random
import java.util.UUID

// random number generator
private val random = Random()

class Nation(
    val uuid: UUID,
    var name: String,
    var capital: Town, // main town in nation, used for nation leadership
) {

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

    private var _needsUpdate = false

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
            val towns = this.towns.asSequence().map { x -> "\"${x}\"" }.joinToString(",", "[", "]")
            val allies = this.allies.asSequence().map { x -> "\"${x}\"" }.joinToString(",", "[", "]")
            val enemies = this.enemies.asSequence().map { x -> "\"${x}\"" }.joinToString(",", "[", "]")

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
        this._needsUpdate = true
    }

    // wrapper to return self as savestate
    // - returns memoized copy if needsUpdate false
    // - otherwise, parses self
    fun getSaveState(): NationSaveState {
        if (this._needsUpdate) {
            this.saveState = NationSaveState(this)
            this._needsUpdate = false
        }
        return this.saveState
    }
}
