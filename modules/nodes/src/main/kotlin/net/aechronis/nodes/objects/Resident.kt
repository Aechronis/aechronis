/**
 * Resident
 * -----------------------------
 * Wrapper around Minecraft player, member
 * of a town.
 * Identified by name which equals unique Minecraft username.
 */

package net.aechronis.nodes.objects

import com.google.gson.JsonPrimitive
import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
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

internal data class VisibleWaypoint(
    val waypoint: Waypoint,
    val owner: Resident,
) {
    val visibilityKey: String get() = "${owner.uuid}:${waypoint.key}"
}

class Resident(val uuid: UUID, val name: String) {
    companion object {
        fun create(player: Player) {
            if (!Nodes.residents.containsKey(player.uuid)) {
                Nodes.residents[player.uuid] = Resident(player.uuid, player.username)
                Nodes.needsSave = true
            }
        }

        fun load(
            uuid: UUID,
            name: String,
            trusted: Boolean,
            waypoints: List<Waypoint> = emptyList(),
            waypointVisibility: Map<String, Boolean> = emptyMap(),
        ) {
            val resident = Resident(uuid, name)
            resident.trusted = trusted
            resident.waypointVisibility.putAll(waypointVisibility)
            waypoints.forEach { waypoint -> resident.loadPermanentWaypoint(waypoint) }
            resident.needsUpdate()
            Nodes.residents[uuid] = resident
        }

        fun count(): Int = Nodes.residents.size

        fun fromPlayer(player: Player): Resident? = Nodes.residents[player.uuid]

        fun fromName(name: String): Resident? {
            val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name)
            if (player != null) return Nodes.residents.values.firstOrNull { it.uuid == player.uuid }
            return Nodes.residents.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

        fun fromUuid(uuid: UUID): Resident? = Nodes.residents[uuid]

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
                fromPlayer(player)?.minimap?.refresh()
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
        private set

    private val permanentWaypointsByName = linkedMapOf<String, Waypoint>()
    val permanentWaypoints: List<Waypoint> get() = permanentWaypointsByName.values.toList()
    private val waypointVisibility = linkedMapOf<String, Boolean>()

    internal fun availablePermanentWaypoints(): List<VisibleWaypoint> = buildList {
        permanentWaypointsByName.values.forEach { waypoint -> add(VisibleWaypoint(waypoint, this@Resident)) }
        Nodes.residents.values.asSequence()
            .filter { owner -> owner !== this@Resident }
            .flatMap { owner -> owner.permanentWaypointsByName.values.asSequence().map { waypoint -> VisibleWaypoint(waypoint, owner) } }
            .filter { visible -> visible.waypoint.isSharedWith(this@Resident) }
            .forEach(::add)
    }

    internal fun visiblePermanentWaypoints(): List<VisibleWaypoint> = availablePermanentWaypoints().filter(::isWaypointVisible)

    internal fun isWaypointVisible(visible: VisibleWaypoint): Boolean = waypointVisibility[visible.visibilityKey]
        ?: defaultWaypointVisibility(visible.waypoint)

    internal fun toggleWaypointVisibility(visible: VisibleWaypoint): Boolean {
        check(visible in availablePermanentWaypoints()) { "Waypoint is not available to this resident" }
        val isVisible = !isWaypointVisible(visible)
        if (isVisible == defaultWaypointVisibility(visible.waypoint)) {
            waypointVisibility.remove(visible.visibilityKey)
        } else {
            waypointVisibility[visible.visibilityKey] = isVisible
        }
        needsUpdate()
        Nodes.needsSave = true
        minimap?.refresh()
        return isVisible
    }

    private fun defaultWaypointVisibility(waypoint: Waypoint): Boolean = waypoint.sharing != WaypointSharing.ALLY

    internal fun availableWaypointSharing(): Set<WaypointSharing> {
        val town = town ?: return setOf(WaypointSharing.PRIVATE)
        if (!isTownLeadership()) return setOf(WaypointSharing.PRIVATE)
        return buildSet {
            add(WaypointSharing.PRIVATE)
            add(WaypointSharing.TOWN)
            if (town.nation != null) {
                add(WaypointSharing.NATION)
                add(WaypointSharing.ALLY)
            }
        }
    }

    internal fun canRemovePermanentWaypoint(visible: VisibleWaypoint): Boolean {
        if (visible.owner === this) return true
        if (!isTownLeadership()) return false
        return when (visible.waypoint.sharing) {
            WaypointSharing.PRIVATE -> false
            WaypointSharing.TOWN -> town?.uuid == visible.waypoint.sharedGroupId
            WaypointSharing.NATION, WaypointSharing.ALLY -> town?.nation?.uuid == visible.waypoint.sharedGroupId
        }
    }

    private fun isTownLeadership(): Boolean {
        val town = town ?: return false
        return this === town.leader || town.officers.contains(this)
    }

    var deathWaypoint: Waypoint? = null
        private set

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

    fun createMinimap(player: Player): Minimap {
        destroyMinimap()
        return Minimap(this, player).also { minimap = it }
    }

    fun destroyMinimap() {
        minimap?.destroy()
        minimap = null
    }

    fun updateMinimap(blockX: Int, blockZ: Int) {
        minimap?.render(blockX, blockZ)
    }

    internal fun validatePermanentWaypointName(inputName: String): Result<String> = runCatching {
        val name = Waypoint.normalizeName(inputName)
        check(!name.equals(Waypoint.DEATH_NAME, ignoreCase = true)) { "The name ${Waypoint.DEATH_NAME} is reserved" }
        check(!permanentWaypointsByName.containsKey(Waypoint.key(name))) { "A waypoint named $name already exists" }
        name
    }

    internal fun createPermanentWaypoint(
        inputName: String,
        x: Int,
        y: Int,
        z: Int,
        sharing: WaypointSharing = WaypointSharing.PRIVATE,
    ): Result<Waypoint> = runCatching {
        val availableSharing = availableWaypointSharing()
        check(sharing in availableSharing) { "Only town leaders and officers can share waypoints with their town, nation, or allies" }
        val sharedGroupId = when (sharing) {
            WaypointSharing.PRIVATE -> null
            WaypointSharing.TOWN -> town?.uuid ?: error("You must be in a town to share a town waypoint")
            WaypointSharing.NATION -> town?.nation?.uuid ?: error("You must be in a nation to share a nation waypoint")
            WaypointSharing.ALLY -> town?.nation?.uuid ?: error("You must be in a nation to share an ally waypoint")
        }
        val waypoint = Waypoint(
            validatePermanentWaypointName(inputName).getOrThrow(),
            x,
            y,
            z,
            sharing,
            sharedGroupId,
        )
        permanentWaypointsByName[waypoint.key] = waypoint
        needsUpdate()
        Nodes.needsSave = true
        if (sharing == WaypointSharing.PRIVATE) minimap?.refresh() else renderMinimaps()
        waypoint
    }

    internal fun removePermanentWaypoint(visible: VisibleWaypoint): Boolean {
        if (!canRemovePermanentWaypoint(visible)) return false
        return visible.owner.removePermanentWaypoint(visible.waypoint.key, visible.waypoint)
    }

    private fun removePermanentWaypoint(key: String, expected: Waypoint?): Boolean {
        val current = permanentWaypointsByName[key] ?: return false
        if (expected != null && current != expected) return false
        permanentWaypointsByName.remove(key)
        val visibilityKey = VisibleWaypoint(current, this).visibilityKey
        clearWaypointVisibility(visibilityKey)
        Nodes.residents.values.asSequence()
            .filter { resident -> resident !== this }
            .forEach { resident -> resident.clearWaypointVisibility(visibilityKey) }
        needsUpdate()
        Nodes.needsSave = true
        if (current.sharing == WaypointSharing.PRIVATE) minimap?.refresh() else renderMinimaps()
        return true
    }

    internal fun recordDeathWaypoint(x: Int, y: Int, z: Int) {
        deathWaypoint = Waypoint(Waypoint.DEATH_NAME, x, y, z)
        minimap?.refresh()
    }

    internal fun clearDeathWaypoint() {
        if (deathWaypoint == null) return
        deathWaypoint = null
        minimap?.refresh()
    }

    private fun loadPermanentWaypoint(waypoint: Waypoint) {
        permanentWaypointsByName.putIfAbsent(waypoint.key, waypoint)
    }

    private fun clearWaypointVisibility(visibilityKey: String) {
        if (waypointVisibility.remove(visibilityKey) != null) needsUpdate()
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
        val waypoints = r.permanentWaypoints
        val waypointVisibility = r.waypointVisibility.toMap()

        override var jsonString: String? = null

        override fun createJsonString(): String {
            val waypointsJson = waypoints.joinToString(",", prefix = "[", postfix = "]") { waypoint ->
                "{" +
                    "\"name\":${JsonPrimitive(waypoint.name)}," +
                    "\"x\":${waypoint.x}," +
                    "\"y\":${waypoint.y}," +
                    "\"z\":${waypoint.z}," +
                    "\"sharing\":${JsonPrimitive(waypoint.sharing.id)}," +
                    "\"sharedGroup\":${waypoint.sharedGroupId?.let { JsonPrimitive(it.toString()) } ?: "null"}" +
                    "}"
            }
            val waypointVisibilityJson = waypointVisibility.entries.joinToString(",", prefix = "{", postfix = "}") { (key, visible) ->
                "${JsonPrimitive(key)}:$visible"
            }
            return (
                "{" +
                    "\"name\":${JsonPrimitive(this.name)}," +
                    "\"town\":${this.town?.let { JsonPrimitive(it) } ?: "null"}," +
                    "\"nation\":${this.nation?.let { JsonPrimitive(it) } ?: "null"}," +
                    "\"trust\":${this.trusted}," +
                    "\"waypoints\":$waypointsJson," +
                    "\"waypointVisibility\":$waypointVisibilityJson" +
                    "}"
                )
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
