/**
 * Resident
 * -----------------------------
 * Wrapper around Minecraft player, member
 * of a town.
 * Identified by name which equals unique Minecraft username.
 */

package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.Nodes.residents
import net.aechronis.nodes.chat.ChatMode
import net.aechronis.nodes.serdes.SaveState
import net.aechronis.nodes.utils.ChatColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID

class Resident(val uuid: UUID, val name: String) {
    companion object {
        fun create(player: Player) {
            if (!Nodes.residents.containsKey(player.uuid)) {
                Nodes.residents[player.uuid] = Resident(player.uuid, player.username)
                Nodes.needsSave = true
            }
        }

        fun load(uuid: UUID, name: String, trusted: Boolean) {
            val resident = Resident(uuid, name)
            resident.trusted = trusted
            resident.needsUpdate()
            Nodes.residents[uuid] = resident
        }

        fun count(): Int = Nodes.residents.size

        fun fromPlayer(player: Player): Resident? = Nodes.residents[player.uuid]

        fun fromName(name: String): Resident? {
            val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name)
            if (player != null) return residents.values.firstOrNull { it.uuid == player.uuid }
            return residents.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

        fun fromUuid(uuid: UUID): Resident? = residents[uuid]

        fun setOnline(resident: Resident, player: Player) {
            resident.town?.let { town ->
                town.playersOnline.add(player)
                town.nation?.playersOnline?.add(player)
            }
        }

        fun setOffline(resident: Resident, player: Player) {
            resident.town?.let { town ->
                town.playersOnline.remove(player)
                town.nation?.playersOnline?.remove(player)
            }
        }

        fun toggleChatMode(resident: Resident, mode: ChatMode): ChatMode {
            resident.chatMode = if (resident.chatMode == mode) ChatMode.GLOBAL else mode
            return resident.chatMode
        }

        fun renderMinimaps() {
            for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                val resident = fromPlayer(player)
                if (resident?.minimap != null) {
                    val position = player.position
                    resident.updateMinimap(Coord.fromBlockCoords(position.x.toInt(), position.z.toInt()))
                }
            }
        }

        internal fun setTrust(resident: Resident, trust: Boolean) {
            resident.trusted = trust
            resident.needsUpdate()
            Nodes.needsSave = true
        }

        internal fun startPlotSelection(resident: Resident) {
            if (resident.player() == null) return
            resident.plotSelectionEnabled = true
            resident.isProtectingChests = false
            resident.clearPlotSelection()
            var task: Task? = null
            task = MinecraftServer.getSchedulerManager()
                .buildTask {
                    if (resident.plotParticleTask !== task) {
                        task?.cancel()
                        return@buildTask
                    }
                    val player = resident.player()
                    if (!resident.plotSelectionEnabled || player == null || !player.isOnline) {
                        if (resident.plotParticleTask === task) resident.plotParticleTask = null
                        task?.cancel()
                        return@buildTask
                    }
                    renderPlotSelectionParticles(player, resident)
                }
                .delay(TaskSchedule.tick(1))
                .repeat(TaskSchedule.tick(10))
                .schedule()
            resident.plotParticleTask = task
        }

        internal fun stopPlotSelection(resident: Resident) {
            resident.plotSelectionEnabled = false
            resident.clearPlotSelection()
        }

        private fun renderPlotSelectionParticles(player: Player, resident: Resident) {
            val first = resident.plotCornerOne ?: return
            val second = resident.plotCornerTwo
            val positions = linkedSetOf<Plot.BlockVec3>()
            if (second == null) {
                positions.add(first)
            } else {
                val minX = minOf(first.x, second.x)
                val minY = minOf(first.y, second.y)
                val minZ = minOf(first.z, second.z)
                val maxX = maxOf(first.x, second.x)
                val maxY = maxOf(first.y, second.y)
                val maxZ = maxOf(first.z, second.z)
                fun addEdge(start: Plot.BlockVec3, end: Plot.BlockVec3) {
                    val xStep = (end.x - start.x).compareTo(0)
                    val yStep = (end.y - start.y).compareTo(0)
                    val zStep = (end.z - start.z).compareTo(0)
                    val length = maxOf(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y), kotlin.math.abs(end.z - start.z))
                    for (i in 0..length) positions.add(Plot.BlockVec3(start.x + i * xStep, start.y + i * yStep, start.z + i * zStep))
                }
                addEdge(Plot.BlockVec3(minX, minY, minZ), Plot.BlockVec3(maxX, minY, minZ))
                addEdge(Plot.BlockVec3(minX, minY, maxZ), Plot.BlockVec3(maxX, minY, maxZ))
                addEdge(Plot.BlockVec3(minX, maxY, minZ), Plot.BlockVec3(maxX, maxY, minZ))
                addEdge(Plot.BlockVec3(minX, maxY, maxZ), Plot.BlockVec3(maxX, maxY, maxZ))
                addEdge(Plot.BlockVec3(minX, minY, minZ), Plot.BlockVec3(minX, minY, maxZ))
                addEdge(Plot.BlockVec3(maxX, minY, minZ), Plot.BlockVec3(maxX, minY, maxZ))
                addEdge(Plot.BlockVec3(minX, maxY, minZ), Plot.BlockVec3(minX, maxY, maxZ))
                addEdge(Plot.BlockVec3(maxX, maxY, minZ), Plot.BlockVec3(maxX, maxY, maxZ))
                addEdge(Plot.BlockVec3(minX, minY, minZ), Plot.BlockVec3(minX, maxY, minZ))
                addEdge(Plot.BlockVec3(maxX, minY, minZ), Plot.BlockVec3(maxX, maxY, minZ))
                addEdge(Plot.BlockVec3(minX, minY, maxZ), Plot.BlockVec3(minX, maxY, maxZ))
                addEdge(Plot.BlockVec3(maxX, minY, maxZ), Plot.BlockVec3(maxX, maxY, maxZ))
            }
            val packets = positions.map { position ->
                ParticlePacket(Particle.WAX_ON, Pos(position.x + 0.5, position.y + 0.5, position.z + 0.5), Vec(0.05, 0.05, 0.05), 0F, 1)
            }.toTypedArray()
            if (packets.isNotEmpty()) player.sendPackets(*packets)
        }

        internal fun startProtectingChests(resident: Resident) {
            if (resident.player() == null) return
            val town = resident.town ?: return
            if (resident !== town.leader && !town.officers.contains(resident)) return
            resident.isProtectingChests = true
        }

        internal fun stopProtectingChests(resident: Resident) {
            if (resident.player() == null) return
            resident.isProtectingChests = false
        }
    }

    var town: Town? = null
    var nation: Nation? = null

    // flag that player trusted by town
    var trusted: Boolean = false

    // player is protecting chest with right click
    var isProtectingChests: Boolean = false

    // temporary town plot selection state
    var plotSelectionEnabled: Boolean = false
    var plotCornerOne: Plot.BlockVec3? = null
    var plotCornerTwo: Plot.BlockVec3? = null
    var plotParticleTask: Task? = null

    fun clearPlotSelection() {
        plotCornerOne = null
        plotCornerTwo = null
        val task = plotParticleTask
        plotParticleTask = null
        task?.cancel()
    }

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
                    "\"trust\":${this.trusted}" +
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
