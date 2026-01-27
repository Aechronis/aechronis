/**
 * Port objects and data structures
 */

package luna.nodes.nodes.objects

import luna.nodes.nodes.Message
import luna.nodes.nodes.Nodes
import luna.nodes.nodes.serdes.SaveState
import luna.nodes.nodes.utils.ChatColor
import net.minestom.server.command.CommandSender

/**
 * Player warpable port
 */
data class Port(
    val name: String,
    val locX: Int,
    val locZ: Int,
    val groups: HashSet<PortGroup> = hashSetOf(),
    val isPublic: Boolean,
) {
    val chunkX = locX.floorDiv(16)
    val chunkZ = locZ.floorDiv(16)

    // json string and memoization flag
    private var saveState = PortSaveState(this)

    private var needsUpdate = false

    /**
     * Port save state for JSON serialization
     */
    class PortSaveState(p: Port) : SaveState {
        val name = p.name
        val locX = p.locX
        val locZ = p.locZ
        val groups = p.groups
        val isPublic = p.isPublic

        override var jsonString: String? = null

        override fun createJsonString(): String {
            // serialize groups as JSON array of strings
            val groupsJson = groups.joinToString(",", "[", "]") { "\"${it.name}\"" }

            val jsonString = (
                "{" +
                    "\"name\":\"${this.name}\"," +
                    "\"x\":$locX," +
                    "\"z\":$locZ," +
                    "\"groups\":$groupsJson," +
                    "\"isPublic\":$isPublic" +
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
    fun getSaveState(): PortSaveState {
        if (this.needsUpdate) {
            this.saveState = PortSaveState(this)
            this.needsUpdate = false
        }
        return this.saveState
    }

    fun printInfo(sender: CommandSender) {
        Message.print(sender, "${ChatColor.AQUA}${ChatColor.BOLD}Port ${this.name}:")
        Message.print(sender, "${ChatColor.AQUA}- (x,z): (${this.locX}, ${this.locZ})")
        Message.print(sender, "${ChatColor.AQUA}- Groups:")
        for (group in this.groups) {
            Message.print(sender, "${ChatColor.AQUA}  - ${group.name}")
        }

        if (this.isPublic) {
            Message.print(sender, "${ChatColor.AQUA}- Public")
        } else {
            // get owner
            val owner = Nodes.getPortOwner(this)
            val ownerName = if (owner !== null) {
                owner.name
            } else {
                "${ChatColor.GRAY}None"
            }
            Message.print(sender, "${ChatColor.AQUA}- Owner: $ownerName")
            Message.print(sender, "${ChatColor.AQUA}- Access: Allies only")
        }
    }
}

/**
 * Group of ports
 */
data class PortGroup(
    val name: String,
) {
    // json string and memoization flag
    private var saveState = PortGroupSaveState(this)

    private var needsUpdate = false

    /**
     * Port group save state for JSON serialization
     */
    class PortGroupSaveState(p: PortGroup) : SaveState {
        val name = p.name

        override var jsonString: String? = null

        override fun createJsonString(): String {
            val jsonString = (
                "{" +
                    "\"name\":\"${this.name}\"" +
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
    fun getSaveState(): PortGroupSaveState {
        if (this.needsUpdate) {
            this.saveState = PortGroupSaveState(this)
            this.needsUpdate = false
        }
        return this.saveState
    }
}
