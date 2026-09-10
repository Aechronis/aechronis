package net.aechronis.nodes.objects

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.serdes.PlotJsonCodec
import net.aechronis.nodes.serdes.SaveState
import net.aechronis.nodes.serdes.snapshotNestedMap
import java.util.UUID

class Plot(
    val name: String,
    cornerOne: BlockVec3,
    cornerTwo: BlockVec3,
) {
    companion object {
        fun at(town: Town, blockX: Int, blockY: Int, blockZ: Int): Plot? = town.plots.values.firstOrNull { it.contains(blockX, blockY, blockZ) }

        fun create(town: Town, name: String, cornerOne: BlockVec3, cornerTwo: BlockVec3): Result<Plot> {
            if (name.isBlank()) return Result.failure(IllegalArgumentException("Plot name cannot be blank"))
            if (town.plots.containsKey(name)) return Result.failure(IllegalArgumentException("A plot with that name already exists"))
            val plot = Plot(name, cornerOne, cornerTwo)
            validate(town, plot)?.let { return Result.failure(IllegalArgumentException(it)) }
            town.plots[name] = plot
            town.needsUpdate()
            Nodes.markWorldDirty()
            return Result.success(plot)
        }

        fun redefine(town: Town, existing: Plot, cornerOne: BlockVec3, cornerTwo: BlockVec3): Result<Plot> {
            if (town.plots[existing.name] !== existing) return Result.failure(IllegalArgumentException("Plot does not belong to this town"))
            val plot = Plot(existing.name, cornerOne, cornerTwo)
            validate(town, plot, existing)?.let { return Result.failure(IllegalArgumentException(it)) }
            plot.copyPermissionsFrom(existing)
            town.plots[existing.name] = plot
            town.needsUpdate()
            Nodes.markWorldDirty()
            return Result.success(plot)
        }

        fun delete(town: Town, plot: Plot): Boolean {
            if (town.plots[plot.name] !== plot) return false
            town.plots.remove(plot.name)
            town.needsUpdate()
            Nodes.markWorldDirty()
            return true
        }

        fun setGroupPermissions(town: Town, plot: Plot, group: PermissionsGroup, permissions: Iterable<TownPermissions>, allowed: Boolean?) {
            permissions.forEach { plot.setGroupPermission(group, it, allowed) }
            town.needsUpdate()
            Nodes.markWorldDirty()
        }

        fun setPlayerPermissions(town: Town, plot: Plot, player: Resident, permissions: Iterable<TownPermissions>, allowed: Boolean?) {
            permissions.forEach { plot.setPlayerPermission(player.uuid, it, allowed) }
            town.needsUpdate()
            Nodes.markWorldDirty()
        }

        internal fun isValid(town: Town, plot: Plot, ignored: Plot? = null): Boolean = validate(town, plot, ignored) == null

        private fun validate(town: Town, plot: Plot, ignored: Plot? = null): String? {
            val width = plot.maxX - plot.minX + 1
            val height = plot.maxY - plot.minY + 1
            val depth = plot.maxZ - plot.minZ + 1
            if (width > Nodes.config.plotMaxWidth || height > Nodes.config.plotMaxHeight || depth > Nodes.config.plotMaxDepth) {
                return "Plot exceeds the maximum allowed dimensions"
            }
            for (chunkX in Math.floorDiv(plot.minX, 16)..Math.floorDiv(plot.maxX, 16)) {
                for (chunkZ in Math.floorDiv(plot.minZ, 16)..Math.floorDiv(plot.maxZ, 16)) {
                    if (Territory.fromCoord(Coord(chunkX, chunkZ))?.town !== town) {
                        return "Every part of a plot must be inside your town's claimed territory"
                    }
                }
            }
            if (town.plots.values.any { it !== ignored && it.overlaps(plot) }) return "Plot overlaps an existing plot"
            return null
        }
    }

    val minX: Int = minOf(cornerOne.x, cornerTwo.x)
    val minY: Int = minOf(cornerOne.y, cornerTwo.y)
    val minZ: Int = minOf(cornerOne.z, cornerTwo.z)
    val maxX: Int = maxOf(cornerOne.x, cornerTwo.x)
    val maxY: Int = maxOf(cornerOne.y, cornerTwo.y)
    val maxZ: Int = maxOf(cornerOne.z, cornerTwo.z)

    private val groupPermissions: MutableMap<PermissionsGroup, MutableMap<TownPermissions, Boolean>> = hashMapOf()
    private val playerPermissions: MutableMap<UUID, MutableMap<TownPermissions, Boolean>> = hashMapOf()

    private var saveState: PlotSaveState = PlotSaveState(this)
    private var needsUpdate = true

    constructor(state: PlotSaveState) : this(
        state.name,
        BlockVec3(state.minX, state.minY, state.minZ),
        BlockVec3(state.maxX, state.maxY, state.maxZ),
    ) {
        state.groupPermissions.forEach { (group, permissions) ->
            groupPermissions[group] = permissions.toMutableMap()
        }
        state.playerPermissions.forEach { (player, permissions) ->
            playerPermissions[player] = permissions.toMutableMap()
        }
        saveState = state
        needsUpdate = false
    }

    fun contains(x: Int, y: Int, z: Int): Boolean = x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    fun overlaps(other: Plot): Boolean = minX <= other.maxX &&
        maxX >= other.minX &&
        minY <= other.maxY &&
        maxY >= other.minY &&
        minZ <= other.maxZ &&
        maxZ >= other.minZ

    fun groupPermission(group: PermissionsGroup, permission: TownPermissions): Boolean? = groupPermissions[group]?.get(permission)

    fun playerPermission(player: UUID, permission: TownPermissions): Boolean? = playerPermissions[player]?.get(permission)

    fun setGroupPermission(group: PermissionsGroup, permission: TownPermissions, value: Boolean?) {
        if (value == null) {
            groupPermissions[group]?.remove(permission)
            if (groupPermissions[group]?.isEmpty() == true) groupPermissions.remove(group)
        } else {
            groupPermissions.getOrPut(group) { hashMapOf() }[permission] = value
        }
        needsUpdate()
    }

    fun setPlayerPermission(player: UUID, permission: TownPermissions, value: Boolean?) {
        if (value == null) {
            playerPermissions[player]?.remove(permission)
            if (playerPermissions[player]?.isEmpty() == true) playerPermissions.remove(player)
        } else {
            playerPermissions.getOrPut(player) { hashMapOf() }[permission] = value
        }
        needsUpdate()
    }

    fun groupPermissionEntries(): Map<PermissionsGroup, Map<TownPermissions, Boolean>> = groupPermissions.mapValues { it.value.toMap() }

    fun playerPermissionEntries(): Map<UUID, Map<TownPermissions, Boolean>> = playerPermissions.mapValues { it.value.toMap() }

    fun copyPermissionsFrom(other: Plot) {
        other.groupPermissionEntries().forEach { (group, permissions) ->
            permissions.forEach { (permission, value) -> setGroupPermission(group, permission, value) }
        }
        other.playerPermissionEntries().forEach { (player, permissions) ->
            permissions.forEach { (permission, value) -> setPlayerPermission(player, permission, value) }
        }
    }

    fun needsUpdate() {
        needsUpdate = true
    }

    fun getSaveState(): PlotSaveState {
        if (needsUpdate) {
            saveState = PlotSaveState(this)
            needsUpdate = false
        }
        return saveState
    }

    data class BlockVec3(val x: Int, val y: Int, val z: Int)

    class PlotSaveState(
        val name: String,
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
        groupPermissions: Map<PermissionsGroup, Map<TownPermissions, Boolean>>,
        playerPermissions: Map<UUID, Map<TownPermissions, Boolean>>,
    ) : SaveState() {
        val groupPermissions: Map<PermissionsGroup, Map<TownPermissions, Boolean>> = groupPermissions.snapshotNestedMap()
        val playerPermissions: Map<UUID, Map<TownPermissions, Boolean>> = playerPermissions.snapshotNestedMap()

        constructor(plot: Plot) : this(
            plot.name,
            plot.minX,
            plot.minY,
            plot.minZ,
            plot.maxX,
            plot.maxY,
            plot.maxZ,
            plot.groupPermissionEntries(),
            plot.playerPermissionEntries(),
        )

        override fun encode(): String = PlotJsonCodec.encode(this)
    }
}
