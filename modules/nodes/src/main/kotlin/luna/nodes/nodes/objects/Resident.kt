/**
 * Resident
 * -----------------------------
 * Wrapper around Minecraft player, member
 * of a town.
 * Identified by name which equals unique Minecraft username.
 */

package luna.nodes.nodes.objects

import luna.nodes.nodes.Message
import luna.nodes.nodes.chat.ChatMode
import luna.nodes.nodes.serdes.SaveState
import luna.nodes.nodes.utils.ChatColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import java.util.UUID

class Resident(val uuid: UUID, val name: String) {
    var town: Town? = null
    var nation: Nation? = null

    // flag that player trusted by town
    var trusted: Boolean = false

    // player is protecting chest with right click
    var isProtectingChests: Boolean = false

    // chat mode config
    var chatMode: ChatMode = ChatMode.GLOBAL

    // town teleport thread
    var teleportThread: Task? = null

    // town invite
    var invitingTown: Town? = null
    var invitingPlayer: Player? = null
    var inviteThread: Task? = null

    // minimap
    var minimap: Minimap? = null

    // save state needs update flag
    private var saveState = ResidentSaveState(this)

    private var needsUpdate = false

    override fun hashCode(): Int = this.uuid.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Resident) return false
        return this.uuid == other.uuid
    }

    // returns player associated with resident
    // returns null when player is offline
    fun player(): Player? = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(this.uuid)

    // ===================================
    // Minimap functions
    // each minimap attached to a resident
    // and only viewable by player
    // ===================================

    fun createMinimap(player: Player, size: Int) {
        // remove any existing minimap
        this.destroyMinimap()

        // create new minimap
        this.minimap = Minimap(this, player, size)
    }

    fun destroyMinimap() {
        val minimap = this.minimap
        if (minimap != null) {
            minimap.destroy()
            this.minimap = null
        }
    }

    // update player minimap if it exists
    fun updateMinimap(coord: Coord) {
        this.minimap?.render(coord)
    }

    // ===================================

    // print resident info
    fun printInfo(sender: CommandSender) {
        val town = this.town?.name ?: "${ChatColor.GRAY}None"
        val nation = this.nation?.name ?: "${ChatColor.GRAY}None"

        Message.print(sender, "${ChatColor.BOLD}Player ${this.name}:")
        Message.print(sender, "- Town${ChatColor.WHITE}: $town")
        Message.print(sender, "- Nation${ChatColor.WHITE}: $nation")
    }

    /**
     * Permissions for town protected chests
     */
    fun hasTownProtectedChestPermissions(town: Town): Boolean {
        if (this.town != town) {
            return false
        }

        if (this === this.town?.leader) {
            return true
        }

        if (this.town?.officers!!.contains(this)) {
            return true
        }

        if (this.trusted) {
            return true
        }

        return false
    }

    /**
     * Immutable save snapshot, must be composed of immutable primitives.
     * Used to generate json string serialization.
     */
    class ResidentSaveState(r: Resident) : SaveState {
        val uuid = r.uuid
        val name = r.name
        val town = r.town?.name
        val nation = r.nation?.name
        val trusted = r.trusted

        override var jsonString: String? = null

        override fun createJsonString(): String {
            val jsonString = (
                "{" +
                    "\"name\":\"${this.name}\"," +
                    "\"town\":${ if (this.town !== null) "\"${this.town}\"" else null }," +
                    "\"nation\":${ if (this.nation !== null) "\"${this.nation}\"" else null }," +
                    "\"trust\":${this.trusted}," +
                    "}"
                )
            return jsonString
        }
    }

    // function to let client flag this object as dirty
    fun needsUpdate() {
        this.needsUpdate = true
    }

    // wrapper to return self as state
    // - returns memoized copy if needsUpdate false
    // - otherwise, parses self
    fun getSaveState(): ResidentSaveState {
        if (this.needsUpdate) {
            this.saveState = ResidentSaveState(this)
            this.needsUpdate = false
        }
        return this.saveState
    }
}
